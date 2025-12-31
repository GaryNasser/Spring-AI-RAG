-- 先删除有外键依赖的表，再删除它依赖的表

DROP TABLE IF EXISTS document_version;
DROP TABLE IF EXISTS document_info;