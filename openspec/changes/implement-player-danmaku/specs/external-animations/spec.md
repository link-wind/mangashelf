## ADDED Requirements

### Requirement: 站内弹幕

平台 MUST 支持按作品与集号存储和展示弹幕，弹幕随播放时间轴滚动。

#### Scenario: 发送弹幕

- **WHEN** 用户在当前播放时间点发送 1–200 字弹幕
- **THEN** 弹幕入库并在同一集下一次播放时按时间点出现

#### Scenario: 断线重连与跳转

- **WHEN** 用户拖动进度条回退
- **THEN** 弹幕层清空并从新时间点重新同步，不重复堆积

### Requirement: 自定义播放器

观看页 MUST 提供自绘播放控制（播放/暂停、进度、时间、倍速、弹幕开关、全屏），
HLS 与 MP4 均可播放。

#### Scenario: 播放 HLS 外部集

- **WHEN** 解析得到 m3u8 直链
- **THEN** 播放器通过 hls.js 播放，控制条可用，进度上报服务端
