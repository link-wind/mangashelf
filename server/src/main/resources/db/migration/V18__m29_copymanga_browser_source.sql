-- M29：拷贝漫画升级为浏览器解析源（代码实现 CopyMangaBrowserSource，id=copymanga）。
-- 停用 V17 的 RANK-only 规则行，避免与代码源重名（registry 也会跳过同名规则源）。
DELETE FROM manga_source_rules WHERE id = 'copymanga';
