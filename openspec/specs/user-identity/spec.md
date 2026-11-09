# user-identity Specification

## Purpose
TBD - created by archiving change implement-manga-reading. Update Purpose after archive.

## Requirements

### Requirement: 用户注册与登录

系统 MUST 支持以登录名和密码注册与登录；密码 MUST 只保存安全哈希。
登录成功后浏览器会话 MUST 以 HttpOnly Cookie 维持；会话数据 MUST 存储在数据库中。

#### Scenario: 新用户注册并登录

- **WHEN** 用户以未占用的登录名和足够强度的密码注册，随后用同一凭据登录
- **THEN** 系统创建用户并以会话 Cookie 返回登录态，GET /me 返回该用户标识与角色

#### Scenario: 错误凭据

- **WHEN** 用户以不存在的登录名或错误密码登录
- **THEN** 系统返回 401，不区分具体原因，不暴露账户是否存在

### Requirement: 无会话访问受保护资源被拒绝

除注册、登录与健康检查外，API MUST 要求已认证会话；
有状态操作 MUST 校验 CSRF 令牌（同源 Cookie + 请求头回传）。

#### Scenario: 未登录访问目录

- **WHEN** 未认证客户端请求 GET /api/v1/works
- **THEN** 系统返回 401

#### Scenario: 缺少 CSRF 头的有状态操作

- **WHEN** 已登录客户端不带 CSRF 令牌请求头调用有状态端点
- **THEN** 系统返回 403
