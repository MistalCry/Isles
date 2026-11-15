package com.isles.platform.setting;

/**
 * 配置值类型（docs/11 §2.2）：决定后台表单控件、服务端校验方式与读取时的解析方法。
 *
 * <p>数据库统一以字符串存储，类型语义只存在于代码注册表 {@link SettingDefinitions}，
 * 避免「库里的值类型」与「代码期望类型」两处维护。
 */
public enum SettingType {

    /** 单行/多行文本。 */
    STRING,

    /** 整数（后台渲染数字输入框）。 */
    INT,

    /** 布尔（后台渲染开关）。 */
    BOOL,

    /** JSON 文本，写入前校验可解析。 */
    JSON
}
