package com.mangashelf.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bearer Token 认证结果（DESIGN 17.1）：principal 为 CurrentUser，
 * authorities 携带 SCOPE_*；仅在请求生命周期内存在，不写入会话。
 */
public class TokenAuthentication extends org.springframework.security.authentication.AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final CurrentUser principal;

    public TokenAuthentication(CurrentUser principal, Set<String> scopes) {
        super(authorities(principal, scopes));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static Collection<? extends GrantedAuthority> authorities(CurrentUser principal,
                                                                       Set<String> scopes) {
        List<GrantedAuthority> list = scopes.stream()
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
                .collect(Collectors.toCollection(ArrayList::new));
        list.add(new SimpleGrantedAuthority("ROLE_" + principal.role()));
        return List.copyOf(list);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
