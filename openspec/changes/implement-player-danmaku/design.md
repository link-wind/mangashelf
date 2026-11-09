# 设计：播放器皮肤、Banner 与弹幕（M13）

## D1. 弹幕数据（V12）

```sql
CREATE TABLE danmaku (
  id, work_id, ordinal, episode_id, user_id, time_ms bigint, content (1..200),
  color (#RRGGBB), created_at
);
CREATE INDEX idx_danmaku_timeline ON danmaku (work_id, ordinal, time_ms);
```

按 (work, ordinal) 取时间轴，与线路无关——切线路弹幕不丢。

## D2. 播放器

- 组件持有 video/hls 生命周期；`src/kind` 变化时重建 hls。
- `onProgress` 每 5 秒与暂停/卸载时回调，调用方写服务端进度。
- 键盘监听绑定在播放器容器（tabIndex=0），避免与页面输入冲突。
- `overlay` 由调用方注入（解析中/失败），播放器不感知业务状态。

## D3. 弹幕层

- rAF 由 timeupdate 驱动（4Hz 足够），游标单调前进；`current < last-2s` 视为 seek 回退，
  清空图层并重置游标。
- 每次生成时选「最久未使用」轨道，轨道冷却 1.2s；位移距离按 `layer.clientWidth` 计算，
  速度与窗口宽度无关。
- 迟到超过 4 秒的弹幕丢弃（跳转后不补播）。

## D4. Banner

`/calendar` 评分降序前 5；6.5 秒自动轮播，点击圆点切换；背景为同封面放大模糊 +
渐变遮罩保证文字可读。
