package io.xjar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Index of encrypted entries written by xjar.
 */
public class XEncryptedIndex implements XConstants {
    private final Set<String> entries;
    private final Set<String> urls;

    public XEncryptedIndex(ClassLoader classLoader) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        Set<String> urls = new LinkedHashSet<>();
        Enumeration<URL> resources = classLoader.getResources(XJAR_INF_DIR + XJAR_INF_IDX);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            String base = baseUrl(resource);
            try (
                    InputStream in = resource.openStream();
                    InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
                    LineNumberReader lnr = new LineNumberReader(isr)
            ) {
                String name;
                while ((name = lnr.readLine()) != null) {
                    if (name.isEmpty()) {
                        continue;
                    }
                    entries.add(name);
                    urls.add(base + name);
                }
            }
        }
        this.entries = Collections.unmodifiableSet(entries);
        this.urls = Collections.unmodifiableSet(urls);
    }

    public boolean containsEntry(String name) {
        return entries.contains(name);
    }

    public boolean containsUrl(URL url) {
        return url != null && urls.contains(url.toString());
    }

    private String baseUrl(URL resource) {
        String url = resource.toString();
        int index = url.lastIndexOf("!/");
        return index >= 0 ? url.substring(0, index + 2) : url;
    }
}
