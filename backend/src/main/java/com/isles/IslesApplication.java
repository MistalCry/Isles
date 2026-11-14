package com.isles;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 群岛（Isles）后端应用入口。
 *
 * <p>模块化单体：按业务领域分包（见 docs/02-架构设计.md §5.1），
 * 领域内再分 controller / service / mapper / entity / dto。
 */
@SpringBootApplication
@MapperScan({
        "com.isles.auth.mapper",
        "com.isles.user.mapper",
        "com.isles.channel.mapper",
        "com.isles.post.mapper",
        "com.isles.comment.mapper",
        "com.isles.notification.mapper",
        "com.isles.search.mapper",
        "com.isles.media.mapper"
})
public class IslesApplication {

    public static void main(String[] args) {
        SpringApplication.run(IslesApplication.class, args);
    }
}
