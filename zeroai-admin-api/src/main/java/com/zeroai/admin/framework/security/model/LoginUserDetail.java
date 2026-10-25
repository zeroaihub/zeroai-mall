package com.zeroai.admin.framework.security.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zeroai.common.core.entity.system.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

@Getter
public class LoginUserDetail implements UserDetails {


    @Serial
    private static final long serialVersionUID = 1L;

    private String token;

    private User user;

    /**
     * 登陆时间
     */
    private Long loginTime;

    /**
     * 过期时间
     */
    private Long expireTime;

    /**
     * 权限列表
     */
    private Set<String> permissions;


    public LoginUserDetail(User user) {
        this.user = user;
    }

    public LoginUserDetail(User user, Set<String> permissions) {
        this.user = user;
        this.permissions = permissions;
    }

    public LoginUserDetail() {
    }

    public LoginUserDetail setPermissions(Set<String> permissions) {
        this.permissions = permissions;
        return this;
    }

    public LoginUserDetail setLoginTime(Long loginTime) {
        this.loginTime = loginTime;
        return this;
    }

    public LoginUserDetail setExpireTime(Long expireTime) {
        this.expireTime = expireTime;
        return this;
    }

    public LoginUserDetail setToken(String token) {
        this.token = token;
        return this;
    }

    public LoginUserDetail setUser(User user) {
        this.user = user;
        return this;
    }

    /**
     * 权限集合。
     * <p>
     * 本项目鉴权走 {@code @ss.hasPermi(...)} → {@link #getPermissions()}，不依赖 Spring Security
     * 的 GrantedAuthority，因此此处只需返回非 null 的空集合。
     * <p>
     * 不能返回 null：Spring Security 7 的 {@code AbstractUserDetailsAuthenticationProvider
     * #createSuccessAuthentication} 会直接对返回值构造 {@code new LinkedHashSet<>(authorities)}，
     * 为 null 时登录接口抛 NPE（表现为 HTTP 200 + code 5000，无任何业务日志）。
     * Security 6 时代该路径取的是 token 上的 authorities，所以 Boot 3 下返回 null 不会暴露。
     *
     * @return 固定空权限集合
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptySet();
    }

    @JsonIgnore
    @Override
    public String getPassword() {
        return this.user.getPassword();
    }

    @Override
    public String getUsername() {
        return this.user.getUserName();
    }

    @JsonIgnore
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @JsonIgnore
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @JsonIgnore
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @JsonIgnore
    @Override
    public boolean isEnabled() {
        return true;
    }
}
