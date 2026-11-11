# 设计：详情页切换数据源

- 源发现：`GET /api/v1/manga-sources` 过滤 `language=zh && SEARCH`，排除当前源。
- 点击源按钮 → `GET /api/v1/manga-sources/{source}/search?q={当前标题}&limit=8`。
- 结果面板展示封面+标题，点击 `navigate('/manga-ext/{id}?source={source}')`。
- 拷贝搜索解析：网页搜索结果中同一作品出现两个链接（封面链接无文字、标题链接
  无封面），按 `/comic/{path_word}` 合并两组字段。
- 搜索经浏览器执行（缓存 5 分钟），首次约 5~8 秒；前端显示“搜索中…”。
