## Why

现有哔哩阅读依赖浏览器逐页截图，冷启动和资源成本较高。依据 DESIGN §12.2、§19、§21，先验证匿名公开章节能否通过 HTTP 取得完整、可解码的页图，再决定是否另立实现变更。

## What Changes

- 增加范围固定的诊断脚本，观察公开阅读器的实际请求，并尝试独立 HTTP 取图。
- 核对总页数、连续页序、图片解码和浏览器关闭后的可用性；区分纯 HTTP、浏览器初始化后 HTTP、仍依赖渲染三种结果。
- 保存脱敏结果与复现步骤，不修改现有生产采集、缓存或阅读器。

## Capabilities

### New Capabilities

无。本次只增加验证工具和记录，使用 `skip_specs: true`。

### Modified Capabilities

无。既有阅读能力要求保持不变。

## Impact

仅涉及 `scripts/`、验证文档与当前 OpenSpec 变更；使用现有 Playwright、Pillow 和 Python 标准库，不新增运行时依赖、数据库表或 API。
