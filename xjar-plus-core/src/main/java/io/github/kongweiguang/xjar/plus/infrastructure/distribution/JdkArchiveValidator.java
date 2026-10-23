/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure.distribution;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 打包前对 JDK 归档执行有界、只读的校验。
 */
public final class JdkArchiveValidator {
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_TOTAL_BYTES = 8L << 30;
    private static final long MAX_ENTRY_BYTES = 2L << 30;
    private static final int MAX_RELEASE_BYTES = 64 * 1024;
    private static final int MAX_LINK_BYTES = 4096;
    private static final int MAX_LINK_DEPTH = 40;
    private static final int MAX_BINARY_HEADER_BYTES = 1024 * 1024;
    private static final int MAX_MACHO_SLICES = 32;
    private static final Pattern JAVA_VERSION = Pattern.compile("^(?:1\\.)?([0-9]+)(?:[._+\\-].*)?$");

    /**
     * 校验器无状态，禁止调用方将其误作可配置服务实例。
     */
    private JdkArchiveValidator() {
    }

    /**
     * 不解压归档，直接验证 JDK 布局、声明的运行时身份和可执行文件架构。
     * 有界元数据校验在打包前完成，避免目标平台错误或路径不安全的 JDK 延迟到启动时才失败；
     * 为兼容厂商 JDK，允许安全的 Unix 相对链接。
     *
     * @param zip 输入 JDK ZIP 归档
     * @param target 受支持的 windows-amd64/windows-arm64/linux-amd64/linux-arm64/darwin-amd64/darwin-arm64 之一
     * @param minimumJava 所需的最低 Java feature 版本
     * @throws IOException 归档格式错误、不安全、超限或不兼容时抛出
     */
    public static void validate(Path zip, String target, int minimumJava) throws IOException {
        if (zip == null || target == null) {
            throw new IllegalArgumentException("JDK archive and target are required");
        }
        if (minimumJava < 1) {
            throw new IllegalArgumentException("minimumJava must be positive");
        }
        final Target platform = Target.parse(target);
        if (!Files.isRegularFile(zip)) {
            throw new IOException("JDK archive is not a regular file: " + zip);
        }
        try (ZipFile archive = ZipFile.builder().setPath(zip).get()) {
            final List<ArchiveNode> nodes = ArchiveReader.read(archive, platform);
            final ArchiveIndex index = indexNodes(nodes);
            LinkValidator.validateParents(nodes, index.byPath());
            LinkValidator.validateLinks(nodes, index);
            final RuntimeFiles runtime = RuntimeLocator.locate(index, platform);
            final Map<String, String> release = ReleaseMetadata.read(archive, runtime.release());
            ReleaseMetadata.validate(release, platform, minimumJava);
            ExecutableHeaderValidator.validate(archive, runtime.javaBinary(), platform);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid JDK archive: " + exception.getMessage(), exception);
        }
    }

    /**
     * 拒绝大小写不敏感的路径冲突后，建立精确路径查找表。
     */
    private static ArchiveIndex indexNodes(List<ArchiveNode> nodes) {
        final Map<String, ArchiveNode> byPath = new HashMap<>();
        final NavigableSet<String> sortedPaths = new TreeSet<>();
        for (ArchiveNode node : nodes) {
            byPath.put(node.path(), node);
            sortedPaths.add(node.path());
        }
        return new ArchiveIndex(byPath, sortedPaths);
    }

    /**
     * 对符号链接目标字节施加硬上限，因为这些字节会被解释为路径指令。
     */
    private static String readLinkTarget(ZipFile archive, ZipArchiveEntry entry, String path) throws IOException {
        if (entry.getSize() > MAX_LINK_BYTES) {
            throw new IOException("JDK archive symlink target is too large: " + path);
        }
        final byte[] bytes;
        try (InputStream input = archive.getInputStream(entry)) {
            bytes = input.readNBytes(MAX_LINK_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_LINK_BYTES || bytes.length != entry.getSize()) {
            throw new IOException("JDK archive symlink target is truncated or oversized: " + path);
        }
        final String target;
        try {
            target = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("JDK archive symlink target is not UTF-8: " + path, exception);
        }
        if (target.indexOf('\\') >= 0 || target.indexOf(':') >= 0 || target.indexOf('\0') >= 0
            || target.startsWith("/")) {
            throw new IOException("JDK archive symlink target is not a safe relative path: " + path);
        }
        return target;
    }

    /**
     * 不进行宿主平台路径归一化，直接返回归档路径中以斜杠分隔的父路径。
     */
    private static String parentPath(String path) {
        final int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    /**
     * 将中央目录策略与路径解析、运行时身份校验分开。
     */
    private static final class ArchiveReader {
        /**
     * 归档清单策略对每个输入 ZIP 独立应用，因此禁止创建实例。
         */
        private ArchiveReader() {
        }

        /**
     * 消费任何条目内容前先读取中央目录清单并实施边界校验。
         */
        private static List<ArchiveNode> read(ZipFile archive, Target target) throws IOException {
            final List<RawNode> rawNodes = readEntries(archive, target);
            if (rawNodes.isEmpty()) {
                throw new IOException("JDK archive is empty");
            }
            return normalizeRoot(archive, rawNodes);
        }

        /**
     * 校验每个条目后，只保留后续检查需要的元数据。
         */
        private static List<RawNode> readEntries(ZipFile archive, Target target) throws IOException {
            final List<RawNode> rawNodes = new ArrayList<>();
            final Enumeration<ZipArchiveEntry> entries = archive.getEntries();
            long totalBytes = 0;
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                final SafePath safePath = safeEntryPath(entry.getName());
                final long size = entry.getSize();
                validateSize(rawNodes.size(), size, totalBytes);
                totalBytes += size;
                validateEntryFeatures(archive, entry, safePath, target, size);
                final boolean symlink = entry.isUnixSymlink();
                rawNodes.add(new RawNode(safePath.path(), safePath.directory(), symlink, entry));
            }
            return rawNodes;
        }

        /**
     * 分别限制条目数和总字节数，防止攻击者控制的归档造成计数溢出。
         */
        private static void validateSize(int entryCount, long size, long totalBytes) throws IOException {
            if (entryCount >= MAX_ENTRIES) {
                throw new IOException("JDK archive exceeds the entry count limit");
            }
            if (size < 0 || size > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES - size) {
                throw new IOException("JDK archive exceeds its declared size limits");
            }
        }

        /**
     * 拒绝不支持的压缩方式、相互矛盾的 Unix 元数据及目标平台不兼容的链接。
         */
        private static void validateEntryFeatures(ZipFile archive, ZipArchiveEntry entry, SafePath safePath,
                                                  Target target, long size) throws IOException {
            validateReadable(archive, entry, safePath);
            validateCompression(entry, safePath);
            validateFileType(entry, safePath);
            validateDirectorySize(safePath, size);
            validateSymlink(target, safePath, entry.isUnixSymlink(), size);
        }

        /**
     * 拒绝加密条目，因为只读预检无法信任其元数据。
         */
        private static void validateReadable(ZipFile archive, ZipArchiveEntry entry, SafePath safePath)
                throws IOException {
            if (!archive.canReadEntryData(entry) || entry.getGeneralPurposeBit().usesEncryption()) {
                throw new IOException("JDK archive contains an unsupported or encrypted entry: " + safePath.path());
            }
        }

        /**
     * 载荷解码格式限制为对应 Go 解压器能够接受的类型。
         */
        private static void validateCompression(ZipArchiveEntry entry, SafePath safePath) throws IOException {
            final int method = entry.getMethod();
            if (method != 0 && method != 8) {
                throw new IOException("JDK archive contains an unsupported compression method: " + safePath.path());
            }
        }

        /**
     * 校验路径图前，要求 ZIP 条目名称和 Unix mode 描述相同的节点类型。
         */
        private static void validateFileType(ZipArchiveEntry entry, SafePath safePath) throws IOException {
            final int unixType = entry.getUnixMode() & 0170000;
            final boolean symlink = entry.isUnixSymlink();
            if (entry.isDirectory() != safePath.directory() || !isSupportedUnixType(unixType, safePath, symlink)) {
                throw new IOException("JDK archive contains an inconsistent or unsupported file type: "
                                      + safePath.path());
            }
        }

        /**
     * 拒绝附加在目录标记上的隐藏载荷，确保解压大小统计准确。
         */
        private static void validateDirectorySize(SafePath safePath, long size) throws IOException {
            if (safePath.directory() && size != 0) {
                throw new IOException("JDK archive directory entries must be empty: " + safePath.path());
            }
        }

        /**
     * Unix 目标仅接受有界 Unix 链接；Windows 解压流程有意不支持链接类型。
         */
        private static void validateSymlink(Target target, SafePath safePath, boolean symlink, long size)
                throws IOException {
            if (symlink && (target.os().equals("windows") || size == 0 || size > MAX_LINK_BYTES)) {
                throw new IOException("JDK archive symlink is unsupported for this target or size: " + safePath.path());
            }
        }

        /**
     * 只检查一次 ZIP Unix 类型，并明确维护 mode 与路径之间的约束。
         */
        private static boolean isSupportedUnixType(int type, SafePath path, boolean symlink) {
            return switch (type) {
                case 0 -> true;
                case 0100000 -> !path.directory();
                case 0040000 -> path.directory();
                case 0120000 -> symlink && !path.directory();
                default -> false;
            };
        }

        /**
     * 去除一层外包装目录，并拒绝归一化后精确或大小写折叠冲突的路径。
         */
        private static List<ArchiveNode> normalizeRoot(ZipFile archive, List<RawNode> rawNodes) throws IOException {
            final String commonRoot = commonRoot(rawNodes);
            final List<ArchiveNode> nodes = new ArrayList<>(rawNodes.size());
            final Set<String> seen = new HashSet<>();
            for (RawNode raw : rawNodes) {
                if (!commonRoot.isEmpty() && raw.path().equals(commonRoot) && raw.directory()) {
                    continue;
                }
                final String path = commonRoot.isEmpty() ? raw.path() : raw.path().substring(commonRoot.length() + 1);
                if (path.isEmpty()) {
                    continue;
                }
                final String collisionKey = path.toLowerCase(Locale.ROOT);
                if (!seen.add(collisionKey)) {
                    throw new IOException("JDK archive contains a duplicate or case-colliding path: " + path);
                }
                final String linkTarget = raw.symlink() ? readLinkTarget(archive, raw.entry(), path) : null;
                nodes.add(new ArchiveNode(path, raw.directory(), raw.symlink(), raw.entry(), linkTarget));
            }
            if (nodes.isEmpty()) {
                throw new IOException("JDK archive contains no files after root normalization");
            }
            return nodes;
        }

        /**
     * 在路径被解压器解释前拒绝依赖平台语义的路径。
         */
        private static SafePath safeEntryPath(String name) throws IOException {
            if (hasUnsafePathName(name)) {
                throw new IOException("JDK archive contains an unsafe entry path");
            }
            final boolean directory = name.endsWith("/");
            final String path = directory ? name.substring(0, name.length() - 1) : name;
            if (path.isEmpty()) {
                throw new IOException("JDK archive contains an empty entry path");
            }
            validatePathParts(name, path);
            return new SafePath(path, directory);
        }

        /**
     * 将宿主平台歧义名称检查与目录穿越路径段检查分开。
         */
        private static boolean hasUnsafePathName(String name) {
            return name == null || name.isEmpty() || name.length() > 4096
                   || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0
                   || name.startsWith("/");
        }

        /**
     * 不通过归一化隐藏问题，直接拒绝空路径段、当前目录段和父目录段。
         */
        private static void validatePathParts(String name, String path) throws IOException {
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw new IOException("JDK archive contains an unsafe entry path: " + name);
                }
            }
        }

        /**
     * 仅移除一层通用外包装目录，与启动器解压器的归档约定一致。
         */
        private static String commonRoot(List<RawNode> nodes) {
            String root = null;
            for (RawNode node : nodes) {
                final int slash = node.path().indexOf('/');
                if (slash < 0) {
                    if (!node.directory()) {
                        return "";
                    }
                    if (root == null) {
                        root = node.path();
                    } else if (!root.equals(node.path())) {
                        return "";
                    }
                    continue;
                }
                final String first = node.path().substring(0, slash);
                if (root == null) {
                    root = first;
                } else if (!root.equals(first)) {
                    return "";
                }
            }
            return root == null ? "" : root;
        }
    }

    /**
     * 通过一次有界图遍历解析归档路径，使链接安全校验独立于 ZIP 解析。
     */
    private static final class LinkValidator {
        /**
     * 链接校验状态仅从单份归档快照推导，因此禁止创建实例。
         */
        private LinkValidator() {
        }

        /**
     * 禁止普通文件充当父目录，同时保留归档内部合法别名。
         */
        private static void validateParents(List<ArchiveNode> nodes, Map<String, ArchiveNode> byPath)
                throws IOException {
            for (ArchiveNode node : nodes) {
                int slash = node.path().lastIndexOf('/');
                while (slash >= 0) {
                    final String parent = node.path().substring(0, slash);
                    final ArchiveNode parentNode = byPath.get(parent);
                    if (parentNode != null && !parentNode.directory() && !parentNode.symlink()) {
                        throw new IOException("JDK archive file is also used as a parent directory: " + parent);
                    }
                    slash = parent.lastIndexOf('/');
                }
            }
        }

        /**
     * 使用前根据完整归档路径图校验每个 Unix 相对符号链接。
         */
        private static void validateLinks(List<ArchiveNode> nodes, ArchiveIndex index) throws IOException {
            for (ArchiveNode node : nodes) {
                if (node.symlink()) {
                    final String candidate = resolveLinkTarget(parentPath(node.path()), node.linkTarget());
                    resolvePath(candidate, index, new HashSet<>(), 0);
                }
            }
        }

        /**
     * 沿已校验链接解析单个路径，不采用宿主平台特有的归一化。
         */
        private static String resolvePath(String candidate, ArchiveIndex index, Set<String> visiting, int depth)
                throws IOException {
            if (depth > MAX_LINK_DEPTH) {
                throw new IOException("JDK archive symlink chain exceeds its depth limit");
            }
            final List<String> parts = splitPath(candidate);
            if (parts.isEmpty()) {
                return "";
            }
            return resolveComponents(parts, index, visiting, depth);
        }

        /**
     * 每个路径段只遍历一次，并将链接解析和父节点类型规则交给各自校验逻辑。
         */
        private static String resolveComponents(List<String> parts, ArchiveIndex index,
                                                Set<String> visiting, int depth) throws IOException {
            final StringBuilder prefix = new StringBuilder();
            for (int position = 0; position < parts.size(); position++) {
                appendComponent(prefix, parts.get(position));
                final String current = prefix.toString();
                final ArchiveNode node = index.byPath().get(current);
                if (node != null && node.symlink()) {
                    return followLink(current, parts, position, node, index, visiting, depth);
                }
                validateParentComponent(current, node, position, parts.size(), index);
            }
            return requireExistingPath(prefix.toString(), index);
        }

        /**
     * 只跟踪当前递归链，允许合法的重复别名并拒绝循环链接。
         */
        private static String followLink(String current, List<String> parts, int position, ArchiveNode node,
                                         ArchiveIndex index, Set<String> visiting, int depth) throws IOException {
            if (!visiting.add(current)) {
                throw new IOException("JDK archive symlink graph contains a cycle: " + current);
            }
            try {
                final String target = resolveLinkTarget(parentPath(current), node.linkTarget());
                final String suffix = String.join("/", parts.subList(position + 1, parts.size()));
                final String combined = suffix.isEmpty() ? target : target + "/" + suffix;
                return resolvePath(combined, index, visiting, depth + 1);
            } finally {
                visiting.remove(current);
            }
        }

        /**
     * 路径段需要目录时，拒绝普通文件或不存在的路径。
         */
        private static void validateParentComponent(String current, ArchiveNode node, int position,
                                                    int partCount, ArchiveIndex index) throws IOException {
            if (position == partCount - 1) {
                return;
            }
            if (node != null && !node.directory()) {
                throw new IOException("JDK archive path uses a non-directory parent: " + current);
            }
            if (node == null && !hasDescendant(current, index)) {
                throw new MissingArchivePathException(current);
            }
        }

        /**
     * 最终路径段之后必须对应实际文件或隐含目录。
         */
        private static String requireExistingPath(String path, ArchiveIndex index) throws IOException {
            if (!index.byPath().containsKey(path) && !hasDescendant(path, index)) {
                throw new MissingArchivePathException(path);
            }
            return path;
        }

        /**
     * 直接追加斜杠分隔的路径段，不解释当前宿主系统的分隔符。
         */
        private static void appendComponent(StringBuilder prefix, String component) {
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(component);
        }

        /**
     * 逐段规范化相对链接目标，并拒绝逃逸归档根目录的路径。
         */
        private static String resolveLinkTarget(String parent, String target) throws IOException {
            if (target.isEmpty()) {
                throw new IOException("JDK archive symlink target is empty");
            }
            final List<String> parts = new ArrayList<>();
            if (!parent.isEmpty()) {
                parts.addAll(List.of(parent.split("/")));
            }
            for (String part : target.split("/", -1)) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (parts.isEmpty()) {
                        throw new IOException("JDK archive symlink escapes its root");
                    }
                    parts.remove(parts.size() - 1);
                } else {
                    parts.add(part);
                }
            }
            return String.join("/", parts);
        }

        /**
     * 拆分规范的归档内部路径；发现错误时直接拒绝，不修补路径别名。
         */
        private static List<String> splitPath(String path) throws IOException {
            if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
                throw new IOException("JDK archive path is not root-relative");
            }
            final List<String> parts = new ArrayList<>();
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    throw new IOException("JDK archive path contains an unresolved parent segment");
                }
                parts.add(part);
            }
            return parts;
        }

        /**
     * 识别隐含目录，但不在文件系统中创建这些目录。
         */
        private static boolean hasDescendant(String path, ArchiveIndex index) {
            final String prefix = path + "/";
            final String first = index.sortedPaths().ceiling(prefix);
            return first != null && first.startsWith(prefix);
        }
    }

    /**
     * 校验版本元数据和可执行文件头前，先选定一个完整且受支持的布局。
     */
    private static final class RuntimeLocator {
        /**
     * 布局解析仅根据已校验归档索引进行纯判断，因此禁止创建实例。
         */
        private RuntimeLocator() {
        }

        /**
     * 定位唯一受支持的运行时布局，包括 macOS bundle 和 Home 外包装目录。
         */
        private static RuntimeFiles locate(ArchiveIndex index, Target target) throws IOException {
            final List<String> roots = List.of("", "Home", "Contents/Home");
            final List<RuntimeFiles> candidates = new ArrayList<>();
            for (String root : roots) {
                candidates.addAll(findRuntimeAtRoot(root, index, target));
            }
            if (candidates.size() != 1) {
                throw new IOException(candidates.isEmpty()
                        ? "JDK archive must contain release and bin/java(.exe) at its root or supported JDK home"
                        : "JDK archive contains multiple ambiguous runtime roots");
            }
            final RuntimeFiles runtime = candidates.get(0);
            validateRuntimeFiles(runtime, target);
            return runtime;
        }

        /**
     * 将 release 文件和相匹配的可执行程序解析为同一个候选根目录。
         */
        private static List<RuntimeFiles> findRuntimeAtRoot(String root, ArchiveIndex index, Target target)
                throws IOException {
            final String binaryName = target.os().equals("windows") ? "java.exe" : "java";
            final ArchiveNode release = resolveRegularFile(appendPath(root, "release"), index, "release metadata");
            final ArchiveNode binary = resolveRegularFile(
                    appendPath(root, "bin/" + binaryName), index, "Java executable");
            if (release == null || binary == null) {
                return List.of();
            }
            return List.of(new RuntimeFiles(release, binary));
        }

        /**
     * 载荷打包前校验元数据长度和 Unix 可执行权限。
         */
        private static void validateRuntimeFiles(RuntimeFiles runtime, Target target) throws IOException {
            if (runtime.release().entry().getSize() > MAX_RELEASE_BYTES) {
                throw new IOException("JDK release metadata exceeds its size limit");
            }
            if (!target.os().equals("windows") && (runtime.javaBinary().entry().getUnixMode() & 0111) == 0) {
                throw new IOException("JDK Java executable is missing its Unix executable mode");
            }
        }

        /**
     * 解析候选路径；仅在路径不存在时返回 null，路径不安全时始终报错。
         */
        private static ArchiveNode resolveRegularFile(String candidate, ArchiveIndex index,
                                                      String description) throws IOException {
            try {
                final String path = LinkValidator.resolvePath(candidate, index, new HashSet<>(), 0);
                final ArchiveNode node = index.byPath().get(path);
                if (node == null || node.directory() || node.symlink()) {
                    return null;
                }
                return node;
            } catch (MissingArchivePathException exception) {
                return null;
            } catch (IOException exception) {
                throw new IOException("Invalid JDK " + description + " path " + candidate + ": "
                                      + exception.getMessage(), exception);
            }
        }

        /**
     * 拼接固定内部路径，不依赖宿主操作系统的路径规则。
         */
        private static String appendPath(String root, String child) {
            return root.isEmpty() ? child : root + "/" + child;
        }
    }

    /**
     * 单独解析厂商 release 元数据，使格式和兼容性策略可分别测试。
     */
    private static final class ReleaseMetadata {
        private static final List<String> REQUIRED_FIELDS = List.of("OS_NAME", "OS_ARCH", "JAVA_VERSION");

        /**
     * release 解析和兼容性检查无需保留状态，因此禁止创建实例。
         */
        private ReleaseMetadata() {
        }

        /**
     * 仅读取并严格解析兼容性检查所需的有界 release 元数据。
         */
        private static Map<String, String> read(ZipFile archive, ArchiveNode entry) throws IOException {
            return parse(decode(readBytes(archive, entry)));
        }

        /**
     * 即使恶意 ZIP 条目声明了错误长度，也限制实际读取字节数。
         */
        private static byte[] readBytes(ZipFile archive, ArchiveNode entry) throws IOException {
            final byte[] bytes;
            try (InputStream input = archive.getInputStream(entry.entry())) {
                bytes = input.readNBytes(MAX_RELEASE_BYTES + 1);
            }
            if (bytes.length != entry.entry().getSize() || bytes.length > MAX_RELEASE_BYTES) {
                throw new IOException("JDK release metadata is truncated or oversized");
            }
            return bytes;
        }

        /**
     * 拒绝格式错误的 UTF-8，不将替换字符误解为元数据。
         */
        private static String decode(byte[] bytes) throws IOException {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("JDK release metadata is not UTF-8", exception);
            }
        }

        /**
     * 仅解析发行包目标校验所需的三个身份字段。
         */
        private static Map<String, String> parse(String text) throws IOException {
            final Map<String, String> values = new HashMap<>();
            for (String line : text.split("\\R")) {
                parseLine(line, values);
            }
            requireFields(values);
            return values;
        }

        /**
     * 忽略无关 release 字段，同时拒绝必需字段重复或格式错误。
         */
        private static void parseLine(String line, Map<String, String> values) throws IOException {
            final int separator = line.indexOf('=');
            if (separator <= 0) {
                return;
            }
            final String key = line.substring(0, separator).trim();
            if (!REQUIRED_FIELDS.contains(key)) {
                return;
            }
            final String value = unquote(key, line.substring(separator + 1).trim());
            if (value.isEmpty() || values.putIfAbsent(key, value) != null) {
                throw new IOException("JDK release metadata has an empty or duplicate " + key);
            }
        }

        /**
     * 只接受成对引号包裹或未加引号的值，拒绝仅有一侧引号的字符串。
         */
        private static String unquote(String key, String value) throws IOException {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            if (value.startsWith("\"") || value.endsWith("\"")) {
                throw new IOException("JDK release metadata has a malformed quoted value for " + key);
            }
            return value;
        }

        /**
     * 防止身份字段缺失时静默采用宿主平台作为默认值。
         */
        private static void requireFields(Map<String, String> values) throws IOException {
            for (String key : REQUIRED_FIELDS) {
                if (!values.containsKey(key)) {
                    throw new IOException("JDK release metadata is missing " + key);
                }
            }
        }

        /**
     * 将厂商元数据与请求目标比对，并拒绝低于声明 feature 下限的运行时。
         */
        private static void validate(Map<String, String> release, Target target, int minimumJava) throws IOException {
            final String os = normalizeOs(release.get("OS_NAME"));
            final String architecture = normalizeArchitecture(release.get("OS_ARCH"));
            if (!target.os().equals(os) || !target.architecture().equals(architecture)) {
                throw new IOException("JDK release target " + release.get("OS_NAME") + "/" + release.get("OS_ARCH")
                                      + " does not match requested " + target.name());
            }
            final int featureVersion = parseFeatureVersion(release.get("JAVA_VERSION"));
            if (featureVersion < minimumJava) {
                throw new IOException("JDK feature version " + featureVersion + " is below minimum " + minimumJava);
            }
        }

        /**
     * 提取 Java feature 版本号，并拒绝不支持的厂商版本表示形式。
         */
        private static int parseFeatureVersion(String value) throws IOException {
            final Matcher matcher = JAVA_VERSION.matcher(value);
            if (!matcher.matches()) {
                throw new IOException("JDK JAVA_VERSION is not a supported feature-version string");
            }
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IOException("JDK JAVA_VERSION feature number is invalid", exception);
            }
        }

        /**
     * 将 release 文件中的操作系统别名规范化为发行包目标词汇。
         */
        private static String normalizeOs(String value) {
            final String normalized = value.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
            if (normalized.startsWith("windows")) {
                return "windows";
            }
            if (normalized.startsWith("linux")) {
                return "linux";
            }
            if (normalized.startsWith("macos") || normalized.startsWith("darwin")) {
                return "darwin";
            }
            return normalized;
        }

        /**
     * 规范化 JDK release 文件中常见的厂商架构名称。
         */
        private static String normalizeArchitecture(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "amd64", "x86_64", "x64" -> "amd64";
                case "arm64", "aarch64" -> "arm64";
                default -> value.toLowerCase(Locale.ROOT);
            };
        }
    }

    /**
     * 选择有界的平台文件头解析器，避免归档元数据伪装成其他平台的可执行文件。
     */
    private static final class ExecutableHeaderValidator {
        /**
     * 可执行文件校验无状态且仅针对输入条目，因此禁止创建实例。
         */
        private ExecutableHeaderValidator() {
        }

        /**
     * 仅读取固定长度的可执行文件前缀，并按请求的目标操作系统分派校验。
         */
        private static void validate(ZipFile archive, ArchiveNode binary, Target target) throws IOException {
            final byte[] header;
            try (InputStream input = archive.getInputStream(binary.entry())) {
                header = input.readNBytes(MAX_BINARY_HEADER_BYTES);
            }
            if (target.os().equals("windows")) {
                validatePe(header, target.architecture());
            } else if (target.os().equals("linux")) {
                validateElf(header, target.architecture());
            } else {
                validateMachO(header, target.architecture());
            }
        }

        /**
     * 校验 PE 签名和 COFF machine 字段，不仅依赖 release 元数据。
         */
        private static void validatePe(byte[] header, String architecture) throws IOException {
            validateDosHeader(header);
            final int position = peHeaderOffset(header);
            validatePeSignature(header, position);
            validateMachineArchitecture(header, position + 4, ByteOrder.LITTLE_ENDIAN,
                    architecture.equals("amd64") ? 0x8664 : 0xaa64, "PE");
        }

        /**
     * 读取 PE 偏移前先拒绝长度不足或不含 DOS 头的可执行文件。
         */
        private static void validateDosHeader(byte[] header) throws IOException {
            if (header.length < 64 || header[0] != 'M' || header[1] != 'Z') {
                throw new IOException("JDK java.exe is not a valid PE executable");
            }
        }

        /**
     * 根据已读取的文件前缀校验无符号 PE 偏移是否越界。
         */
        private static int peHeaderOffset(byte[] header) throws IOException {
            final long offset = Integer.toUnsignedLong(ByteBuffer.wrap(header, 0x3c, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt());
            if (offset > header.length - 6L) {
                throw new IOException("JDK PE header is outside the bounded executable prefix");
            }
            return Math.toIntExact(offset);
        }

        /**
     * 读取 machine 标识前先校验四字节 PE 签名。
         */
        private static void validatePeSignature(byte[] header, int position) throws IOException {
            if (header[position] != 'P' || header[position + 1] != 'E'
                || header[position + 2] != 0 || header[position + 3] != 0) {
                throw new IOException("JDK java.exe has an invalid PE signature");
            }
        }

        /**
     * 校验所选 Linux 目标对应的 ELF 位数、字节序和 e_machine 字段。
         */
        private static void validateElf(byte[] header, String architecture) throws IOException {
            validateElfIdentity(header);
            final ByteOrder order = elfByteOrder(header);
            if (Byte.toUnsignedInt(header[4]) != 2) {
                throw new IOException("JDK ELF executable is not 64-bit");
            }
            validateMachineArchitecture(header, 18, order,
                    architecture.equals("amd64") ? 62 : 183, "ELF");
        }

        /**
     * 读取依赖格式的字段前，先拒绝截断或非 ELF 文件前缀。
         */
        private static void validateElfIdentity(byte[] header) throws IOException {
            if (header.length < 20 || header[0] != 0x7f || header[1] != 'E'
                || header[2] != 'L' || header[3] != 'F') {
                throw new IOException("JDK java is not a valid ELF executable");
            }
        }

        /**
     * 将 ELF 声明的字节序标记转换为有界的基础类型读取顺序。
         */
        private static ByteOrder elfByteOrder(byte[] header) throws IOException {
            return switch (Byte.toUnsignedInt(header[5])) {
                case 1 -> ByteOrder.LITTLE_ENDIAN;
                case 2 -> ByteOrder.BIG_ENDIAN;
                default -> throw new IOException("JDK ELF byte order is invalid");
            };
        }

        /**
     * 校验单架构 Mach-O，或校验有界通用二进制文件头中的目标架构切片。
         */
        private static void validateMachO(byte[] header, String architecture) throws IOException {
            validateMachOIdentity(header);
            final int magic = magic(header);
            if (isThinMachO(magic)) {
                validateThinMachO(header, magic, architecture);
                return;
            }
            if (!isFatMachO(magic)) {
                throw new IOException("JDK java has an unknown Mach-O header");
            }
            validateUniversalMachO(header, magic, architecture);
        }

        /**
     * 确保输入字节足以包含单架构或通用 Mach-O 识别头。
         */
        private static void validateMachOIdentity(byte[] header) throws IOException {
            if (header.length < 8) {
                throw new IOException("JDK java is not a valid Mach-O executable");
            }
        }

        /**
     * 读取与字节序无关的起始 magic 值。
         */
        private static int magic(byte[] header) {
            return ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        }

        /**
     * 识别所有受支持的单架构 Mach-O magic 变体。
         */
        private static boolean isThinMachO(int magic) {
            return magic == 0xfeedface || magic == 0xfeedfacf || magic == 0xcefaedfe || magic == 0xcffaedfe;
        }

        /**
     * 识别两种字节序下的 32/64 位通用文件头。
         */
        private static boolean isFatMachO(int magic) {
            return magic == 0xcafebabe || magic == 0xcafebabf
                   || magic == 0xbebafeca || magic == 0xbfbafeca;
        }

        /**
     * 按单架构 Mach-O 文件头声明的字节序校验 CPU 类型。
         */
        private static void validateThinMachO(byte[] header, int magic, String architecture) throws IOException {
            final ByteOrder order = magic == 0xfeedface || magic == 0xfeedfacf
                    ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            final int expectedCpu = expectedMachOCpu(architecture);
            final int actualCpu = ByteBuffer.wrap(header, 4, 4).order(order).getInt();
            if (actualCpu != expectedCpu) {
                throw new IOException("JDK Mach-O architecture does not match " + architecture);
            }
        }

        /**
     * 校验通用文件头中有界的切片表，并要求其中包含请求的 CPU 类型。
         */
        private static void validateUniversalMachO(byte[] header, int magic, String architecture) throws IOException {
            final ByteOrder order = magic == 0xcafebabe || magic == 0xcafebabf
                    ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            final boolean fat64 = magic == 0xcafebabf || magic == 0xbfbafeca;
            final int stride = fat64 ? 32 : 20;
            final int count = ByteBuffer.wrap(header, 4, 4).order(order).getInt();
            if (count < 1 || count > MAX_MACHO_SLICES || header.length < 8L + (long) count * stride) {
                throw new IOException("JDK universal Mach-O header is truncated or has too many slices");
            }
            for (int index = 0; index < count; index++) {
                final int cpu = ByteBuffer.wrap(header, 8 + index * stride, 4).order(order).getInt();
                if (cpu == expectedMachOCpu(architecture)) {
                    return;
                }
            }
            throw new IOException("JDK universal Mach-O binary has no " + architecture + " slice");
        }

        /**
     * 仅映射两种受支持的 64 位 macOS 目标架构。
         */
        private static int expectedMachOCpu(String architecture) {
            return architecture.equals("amd64") ? 0x01000007 : 0x0100000c;
        }

        /**
     * 所属文件格式校验通过后读取 16 位 machine 标识，并拒绝架构不匹配的情况。
         */
        private static void validateMachineArchitecture(byte[] header, int offset, ByteOrder order,
                                                        int expected, String format) throws IOException {
            final int actual = Short.toUnsignedInt(ByteBuffer.wrap(header, offset, 2).order(order).getShort());
            if (actual != expected) {
                throw new IOException("JDK " + format + " machine architecture does not match its target");
            }
        }
    }

    private record SafePath(String path, boolean directory) {
    }

    private record RawNode(String path, boolean directory, boolean symlink, ZipArchiveEntry entry) {
    }

    private record ArchiveNode(String path, boolean directory, boolean symlink,
                               ZipArchiveEntry entry, String linkTarget) {
    }

    private record ArchiveIndex(Map<String, ArchiveNode> byPath, NavigableSet<String> sortedPaths) {
    }

    private static final class MissingArchivePathException extends IOException {
        private static final long serialVersionUID = 1L;

        /**
     * 允许可选布局探测区分候选路径缺失和符号链接格式错误。
         */
        private MissingArchivePathException(String path) {
            super("JDK archive symlink refers to a missing path: " + path);
        }
    }

    private record RuntimeFiles(ArchiveNode release, ArchiveNode javaBinary) {
    }

    private record Target(String name, String os, String architecture) {
        /**
     * 拒绝目标平台别名，使产物命名和校验统一采用规范词汇。
         */
        private static Target parse(String value) {
            return switch (value) {
                case "windows-amd64" -> new Target(value, "windows", "amd64");
                case "windows-arm64" -> new Target(value, "windows", "arm64");
                case "linux-amd64" -> new Target(value, "linux", "amd64");
                case "linux-arm64" -> new Target(value, "linux", "arm64");
                case "darwin-amd64" -> new Target(value, "darwin", "amd64");
                case "darwin-arm64" -> new Target(value, "darwin", "arm64");
                default -> throw new IllegalArgumentException("unsupported target " + value);
            };
        }
    }
}
