package com.isles;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 群岛（Isles）后端应用入口。
 *
 * <p>模块化单体：按业务领域分包（见 docs/02-架构设计.md §5.1），
 * 领域内再分 controller / service / mapper / entity / dto。
 *
 * <p>Mapper 扫描按 {@link Mapper} 注解过滤（而非逐包枚举），新增业务模块无需改动本文件；
 * 各模块 mapper/row 下的查询行对象不带注解，不会被误注册为 Mapper。
 * 启动时可能出现 MyBatis "Bean already defined" 的 WARN（与启动器自动扫描重叠），
 * 属于幂等跳过，不影响功能。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.isles")
@MapperScan(basePackages = "com.isles", annotationClass = Mapper.class)
public class IslesApplication {

    public static void main(String[] args) {
        SpringApplication.run(IslesApplication.class, args);
    }
}
