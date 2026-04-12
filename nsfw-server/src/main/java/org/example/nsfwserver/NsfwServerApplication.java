package org.example.nsfwserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NsfwServerApplication {

    public static void main(String[] args) {
        // 设置Headless模式，确保在Linux Docker等无显示环境正常运行
        System.setProperty("java.awt.headless", "true");

        SpringApplication.run(NsfwServerApplication.class, args);
    }

}
