# `generate_mybatis_code.py` 使用说明

## 1. 脚本位置

脚本文件：

`.\generate_mybatis_code.py`

该脚本是一个独立的 Python 代码生成工具，用于根据 MySQL 表结构生成以下三类文件：

1. Entity 实体类
2. MyBatis Mapper 接口
3. Mapper XML

生成效果与本次在项目中生成的 `entity`、`mapper`、`xml` 保持一致。

---

## 2. 前置依赖

需要本机安装：

1. Python 3
2. `PyMySQL`

安装命令：

```bash
pip install PyMySQL
```

---

## 3. 生成内容说明

脚本会为每张表生成：

1. `Entity`
   类和字段都带 `@PrivateMcpToolParam(description = "...")`
2. `Mapper`
   包含标准增删改查方法
3. `Mapper XML`
   包含：
   - `resultMap`
   - 按主键查询单条
   - 按条件动态查询
   - 按条件动态统计
   - 动态 `insert`
   - 动态 `update`
   - 按主键删除

动态查询的 `where` 部分使用 `<if>` 生成非空条件。

`insert` 和 `update` 会自动跳过：

1. 自增主键列
2. `STORED GENERATED` 生成列

---

## 4. 输出目录规则

脚本只需要配置一个基础输出目录：`base_output_dir`。

假设：

- `base_output_dir = D:\codegen-output`
- `base_package = com.kevin.mcp`

那么生成目录如下：

```text
D:\codegen-output
└─ src
   └─ main
      ├─ java
      │  └─ com
      │     └─ kevin
      │        └─ mcp
      │           ├─ entity
      │           └─ mapper
      └─ resources
         └─ mapper
```

对应文件位置：

1. Entity：
   `src/main/java/{base_package}/entity`
2. Mapper：
   `src/main/java/{base_package}/mapper`
3. XML：
   `src/main/resources/mapper`

---

## 5. 固定配置方式

直接修改脚本顶部的 `CONFIG`：

```python
CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "database": "demo",
    "username": "root",
    "password": "root123",
    "author": "Kevin",
    "date_text": "",
    "base_output_dir": r"D:\codegen-output",
    "base_package": "com.example.codegen",
    "entity_type_annotation": "@PrivateMcpToolParam",
    "entity_type_annotation_import": "com.kevin.mcp.annotation.PrivateMcpToolParam",
    "entity_field_annotation": "@PrivateMcpToolParam",
    "entity_field_annotation_import": "com.kevin.mcp.annotation.PrivateMcpToolParam",
    "entity_type_description_attr": "description",
    "entity_field_description_attr": "description",
    "entity_extra_annotations": [
        "@Data",
        "@NoArgsConstructor",
        "@AllArgsConstructor",
        "@EqualsAndHashCode",
    ],
    "entity_extra_imports": [
        "lombok.AllArgsConstructor",
        "lombok.Data",
        "lombok.EqualsAndHashCode",
        "lombok.NoArgsConstructor",
    ],
    "table_include": [],
    "table_exclude": [],
}
```

### 配置项说明

1. `host`
   MySQL 主机地址
2. `port`
   MySQL 端口
3. `database`
   数据库名
4. `username`
   数据库用户名
5. `password`
   数据库密码
6. `author`
   生成代码中的 Javadoc 作者
7. `date_text`
   生成代码中的日期。留空时自动使用当天日期
8. `base_output_dir`
   代码输出根目录
9. `base_package`
   Java 基础包名
10. `entity_type_annotation`
    类级注解名称
11. `entity_type_annotation_import`
    类级注解 import
12. `entity_field_annotation`
    字段级注解名称
13. `entity_field_annotation_import`
    字段级注解 import
14. `entity_type_description_attr`
    类级注解中描述属性名，默认是 `description`
15. `entity_field_description_attr`
    字段级注解中描述属性名，默认是 `description`
16. `entity_extra_annotations`
    实体类附加注解列表
17. `entity_extra_imports`
    实体类附加 import 列表
18. `table_include`
    只生成指定表。空列表表示不过滤
19. `table_exclude`
    排除指定表

---

## 6. 命令行覆盖方式

如果你不想每次都改脚本，可以通过命令行临时覆盖配置。

支持参数：

```bash
python generate_mybatis_code.py --help
```

主要参数如下：

1. `--host`
2. `--port`
3. `--database`
4. `--username`
5. `--password`
6. `--author`
7. `--date-text`
8. `--base-output-dir`
9. `--base-package`
10. `--table-include`
11. `--table-exclude`

---

## 7. 常用命令示例

### 7.1 生成整个数据库

```bash
python D:\Codes\Origin-Lightyear\enterprise-mcp\scripts\generate_mybatis_code.py ^
  --host 127.0.0.1 ^
  --port 3306 ^
  --database demo_erp ^
  --username root ^
  --password root123 ^
  --base-output-dir D:\codegen-output ^
  --base-package com.kevin.mcp ^
  --author Kevin ^
  --date-text 2026/7/31
```

### 7.2 只生成指定表

```bash
python D:\Codes\Origin-Lightyear\enterprise-mcp\scripts\generate_mybatis_code.py ^
  --host 127.0.0.1 ^
  --port 3306 ^
  --database demo_erp ^
  --username root ^
  --password root123 ^
  --base-output-dir D:\codegen-output ^
  --base-package com.kevin.mcp ^
  --table-include sys_user org_store pos_order
```

### 7.3 排除部分表

```bash
python D:\Codes\Origin-Lightyear\enterprise-mcp\scripts\generate_mybatis_code.py ^
  --host 127.0.0.1 ^
  --port 3306 ^
  --database demo_erp ^
  --username root ^
  --password root123 ^
  --base-output-dir D:\codegen-output ^
  --base-package com.kevin.mcp ^
  --table-exclude flyway_schema_history
```

---

## 8. 生成后的代码特点

### 8.1 Entity

实体类包含：

1. 类注释
2. 字段注释
3. `@PrivateMcpToolParam(description = "表注释/字段注释")`
4. Lombok 注解：
   - `@Data`
   - `@NoArgsConstructor`
   - `@AllArgsConstructor`
   - `@EqualsAndHashCode`

### 8.2 Mapper 接口

默认生成的方法：

1. `insert`
2. `selectById`
3. `selectByCondition`
4. `countByCondition`
5. `updateById`
6. `deleteById`

### 8.3 Mapper XML

默认生成：

1. `resultMap`
2. `Base_Column_List`
3. 主键查询
4. 动态条件查询
5. 动态条件统计
6. 动态新增
7. 动态更新
8. 主键删除

---

## 9. 当前支持的字段类型

当前脚本已内置以下 MySQL 到 Java 的类型映射：

1. `bigint` -> `Long`
2. `int` -> `Integer`
3. `tinyint` -> `Integer`
4. `decimal` -> `BigDecimal`
5. `varchar` -> `String`
6. `datetime` -> `LocalDateTime`
7. `date` -> `LocalDate`

如果后续数据库出现新的字段类型，比如 `text`、`char`、`timestamp`、`json`，可以在脚本中的 `mysql_type_to_java` 方法里继续扩展。

---

## 10. 常见问题

### 10.1 提示缺少 `PyMySQL`

执行：

```bash
pip install PyMySQL
```

### 10.2 没有生成任何表

优先检查：

1. `database` 是否正确
2. 数据库连接账号是否有读取 `information_schema` 和目标库表结构的权限
3. `table_include` 是否误配
4. `table_exclude` 是否把目标表排除了

### 10.3 生成目录不符合预期

检查：

1. `base_output_dir`
2. `base_package`

脚本会严格根据这两个值自动推导输出目录。

### 10.4 想替换实体注解

只需要修改：

1. `entity_type_annotation`
2. `entity_type_annotation_import`
3. `entity_field_annotation`
4. `entity_field_annotation_import`

如果不用 `@PrivateMcpToolParam`，也可以替换成你自己的注解。

---

## 11. 推荐使用方式

如果是固定项目长期使用，推荐直接改 `CONFIG`。

如果是临时对不同数据库反复生成，推荐保留默认配置，然后通过命令行参数覆盖：

1. 数据库连接
2. 输出目录
3. 基础包名
4. 指定表范围

这样脚本本身不用反复改动，更适合做通用工具。
