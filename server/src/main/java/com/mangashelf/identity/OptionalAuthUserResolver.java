package com.mangashelf.identity;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把当前用户注入标注了 {@link OptionalAuthUser} 的参数；未登录返回 {@link CurrentUser#GUEST}。
 * 访客的可读范围由数据层 {@code owner_id = viewerId OR is_public} 保证——全零 ID 不匹配任何所有者，
 * 故访客天然只看到 PUBLIC+PUBLISHED 内容（DESIGN 3.2/3.3）。
 */
@Component
public class OptionalAuthUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(OptionalAuthUser.class)
                && parameter.getParameterType().equals(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        return CurrentUser.GUEST;
    }
}
