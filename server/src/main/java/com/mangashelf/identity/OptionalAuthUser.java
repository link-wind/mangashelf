package com.mangashelf.identity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记控制器参数由会话注入当前用户；未登录时注入 {@link CurrentUser#GUEST} 而非抛 401。
 * 用于「公开内容可被访客读取、私有内容仍需登录」的读端点（访客浏览）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OptionalAuthUser {
}
