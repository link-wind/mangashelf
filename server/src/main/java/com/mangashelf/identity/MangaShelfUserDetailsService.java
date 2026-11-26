package com.mangashelf.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 从 users 表加载登录用户；登录成功后由 AuthController 换成 CurrentUser 作为 principal。 */
@Service
public class MangaShelfUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public MangaShelfUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = users.findByLoginOrEmail(identifier).orElseThrow(() ->
                new UsernameNotFoundException("login not found"));
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.login())
                .password(user.passwordHash())
                .roles(user.role())
                .disabled(!User.STATUS_ACTIVE.equals(user.status()))
                .build();
    }
}
