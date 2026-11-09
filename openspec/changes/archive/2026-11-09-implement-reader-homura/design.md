# 设计：阅读器对齐 HOMURA（M22）

## D1. 手势

`classifyGesture`：tap（<300ms 且 <8px，按 30%/40%/30% 分区）；swipe（横向 |dx|>24
且 >|dy|*1.2，且 >max(48px, 14% 宽) 或速度>0.45px/ms）；其余 cancel。
返回判别联合（tap/swipe/cancel 各自带 zone 类型），调用处分支自动收窄。

## D2. 双页

`buildSpreads(total, mode)`：single 每页一组；double 两两成组；double-cover 先单排
封面页再两两成组。RTL 时组内页序反转（右页在前）。滑杆以组为单位；续读页码换算：
`spread = floor((page - 1 - base) / 2)`，base 为封面单排偏移。

## D3. 设置持久化

`ms-manga-reader-settings`（localStorage，全局）：dir / pageMode / anim / hd。
按作品分别记忆暂不做。
