## ADDED Requirements

### Requirement: 动画媒体直连缓存与 Range 交付保障

动画播放接口 MUST 批量查询视频、封面与字幕资产对象键，并为视频、封面与字幕分别下发明确的 `Cache-Control` 响应头；视频字节 MUST 经对象存储直出（Java API 转发 0 字节媒体流量），反向代理 `/media/` MUST 支持 HTTP/1.1 长连接、流式透传与字节 `Range` 分段播放。

#### Scenario: 动画首段加载与中段拖动返回 206

- **WHEN** 客户端使用播放接口签发的视频地址先后发起 `Range: bytes=0-65535` 与中段字节区间请求
- **THEN** 对象存储直接返回 `206 Partial Content`、`Accept-Ranges: bytes`、`Content-Range`、`Cache-Control` 与 `ETag`，Java API 不经手视频字节流
