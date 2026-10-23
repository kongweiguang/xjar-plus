package sample.boot4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    /** Use the actual Boot launcher and application context so successful HTTP probes exercise framework class loading. */
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
