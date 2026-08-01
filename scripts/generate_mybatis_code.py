#!/usr/bin/env python3
"""
独立的 MyBatis 代码生成脚本。

生成内容：
1. Entity 实体类
2. Mapper 接口
3. Mapper XML

使用方式：
1. 修改下方 CONFIG 中的数据库连接和输出配置
2. 安装驱动：pip install PyMySQL
3. 运行：python generate_mybatis_code.py
"""

from __future__ import annotations

import argparse
import datetime as dt
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


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


@dataclass(frozen=True)
class ColumnMeta:
    table_name: str
    column_name: str
    column_type: str
    data_type: str
    is_nullable: str
    column_key: str
    extra: str
    column_comment: str
    table_comment: str
    ordinal_position: int

    @property
    def field_name(self) -> str:
        return to_camel(self.column_name, upper=False)

    @property
    def java_type(self) -> str:
        return mysql_type_to_java(self.data_type)

    @property
    def is_primary_key(self) -> bool:
        return self.column_key == "PRI"

    @property
    def is_auto_increment(self) -> bool:
        return self.extra == "auto_increment"

    @property
    def is_stored_generated(self) -> bool:
        return "STORED GENERATED" in self.extra

    @property
    def is_string(self) -> bool:
        return self.data_type == "varchar"


def detect_date_text() -> str:
    configured = str(CONFIG.get("date_text", "")).strip()
    if configured:
        return configured
    today = dt.date.today()
    return f"{today.year}/{today.month}/{today.day}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成 MyBatis Entity、Mapper 和 XML")
    parser.add_argument("--host", help="数据库主机")
    parser.add_argument("--port", type=int, help="数据库端口")
    parser.add_argument("--database", help="数据库名")
    parser.add_argument("--username", help="数据库用户名")
    parser.add_argument("--password", help="数据库密码")
    parser.add_argument("--author", help="代码注释作者")
    parser.add_argument("--date-text", help="代码注释日期，例如 2026/7/31")
    parser.add_argument("--base-output-dir", help="生成代码的基础输出目录")
    parser.add_argument("--base-package", help="生成代码的基础包名，例如 com.example.codegen")
    parser.add_argument(
        "--table-include",
        nargs="*",
        help="只生成指定表，多个表用空格分隔",
    )
    parser.add_argument(
        "--table-exclude",
        nargs="*",
        help="排除指定表，多个表用空格分隔",
    )
    return parser.parse_args()


def apply_cli_overrides(args: argparse.Namespace) -> None:
    if args.host:
        CONFIG["host"] = args.host
    if args.port is not None:
        CONFIG["port"] = args.port
    if args.database:
        CONFIG["database"] = args.database
    if args.username:
        CONFIG["username"] = args.username
    if args.password:
        CONFIG["password"] = args.password
    if args.author:
        CONFIG["author"] = args.author
    if args.date_text:
        CONFIG["date_text"] = args.date_text
    if args.base_output_dir:
        CONFIG["base_output_dir"] = args.base_output_dir
    if args.base_package:
        CONFIG["base_package"] = args.base_package
    if args.table_include is not None:
        CONFIG["table_include"] = args.table_include
    if args.table_exclude is not None:
        CONFIG["table_exclude"] = args.table_exclude


def to_camel(name: str, upper: bool) -> str:
    parts = [part.lower() for part in name.split("_") if part]
    if not parts:
        return name
    if upper:
        return "".join(part[:1].upper() + part[1:] for part in parts)
    head, *tail = parts
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def mysql_type_to_java(data_type: str) -> str:
    mapping = {
        "bigint": "Long",
        "int": "Integer",
        "tinyint": "Integer",
        "decimal": "BigDecimal",
        "varchar": "String",
        "datetime": "LocalDateTime",
        "date": "LocalDate",
    }
    if data_type not in mapping:
        raise ValueError(f"不支持的 MySQL 类型：{data_type}")
    return mapping[data_type]


def escape_java(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def normalize_comment(text: str, fallback: str) -> str:
    value = (text or "").strip()
    return value if value else fallback


def resolve_base_package() -> str:
    return str(CONFIG["base_package"]).strip().rstrip(".")


def resolve_base_output_dir() -> Path:
    return Path(str(CONFIG["base_output_dir"])).expanduser().resolve()


def resolve_entity_package() -> str:
    return f"{resolve_base_package()}.entity"


def resolve_mapper_package() -> str:
    return f"{resolve_base_package()}.mapper"


def resolve_entity_output_dir() -> Path:
    return resolve_base_output_dir() / "src" / "main" / "java" / Path(resolve_entity_package().replace(".", "/"))


def resolve_mapper_output_dir() -> Path:
    return resolve_base_output_dir() / "src" / "main" / "java" / Path(resolve_mapper_package().replace(".", "/"))


def resolve_xml_output_dir() -> Path:
    return resolve_base_output_dir() / "src" / "main" / "resources" / "mapper"


def load_metadata() -> OrderedDict[str, list[ColumnMeta]]:
    try:
        import pymysql
    except ModuleNotFoundError as exc:
        raise SystemExit("缺少 PyMySQL 依赖，请先执行：pip install PyMySQL") from exc

    sql = """
SELECT
    c.TABLE_NAME,
    c.COLUMN_NAME,
    c.COLUMN_TYPE,
    c.DATA_TYPE,
    c.IS_NULLABLE,
    c.COLUMN_KEY,
    c.EXTRA,
    c.COLUMN_COMMENT,
    t.TABLE_COMMENT,
    c.ORDINAL_POSITION
FROM information_schema.COLUMNS c
JOIN information_schema.TABLES t
    ON c.TABLE_SCHEMA = t.TABLE_SCHEMA
   AND c.TABLE_NAME = t.TABLE_NAME
WHERE c.TABLE_SCHEMA = %s
ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
""".strip()
    connection = pymysql.connect(
        host=str(CONFIG["host"]),
        port=int(CONFIG["port"]),
        user=str(CONFIG["username"]),
        password=str(CONFIG["password"]),
        database=str(CONFIG["database"]),
        charset="utf8mb4",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql, (str(CONFIG["database"]),))
            rows = cursor.fetchall()
    finally:
        connection.close()

    include_tables = set(CONFIG.get("table_include", []))
    exclude_tables = set(CONFIG.get("table_exclude", []))
    grouped: OrderedDict[str, list[ColumnMeta]] = OrderedDict()
    for row in rows:
        table_name = row["TABLE_NAME"]
        if include_tables and table_name not in include_tables:
            continue
        if table_name in exclude_tables:
            continue
        grouped.setdefault(table_name, []).append(
            ColumnMeta(
                table_name=row["TABLE_NAME"],
                column_name=row["COLUMN_NAME"],
                column_type=row["COLUMN_TYPE"],
                data_type=row["DATA_TYPE"],
                is_nullable=row["IS_NULLABLE"],
                column_key=row["COLUMN_KEY"],
                extra=row["EXTRA"],
                column_comment=row["COLUMN_COMMENT"],
                table_comment=row["TABLE_COMMENT"],
                ordinal_position=int(row["ORDINAL_POSITION"]),
            )
        )
    return grouped


def xml_test_expression(column: ColumnMeta) -> str:
    if column.is_string:
        return f"{column.field_name} != null and {column.field_name} != ''"
    return f"{column.field_name} != null"


def annotation_line(annotation_name: str, attr_name: str, description: str) -> str:
    return f"{annotation_name}({attr_name} = \"{escape_java(description)}\")"


def unique_preserve_order(items: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            result.append(item)
    return result


def find_primary_key(columns: Iterable[ColumnMeta]) -> ColumnMeta:
    columns_list = list(columns)
    for column in columns_list:
        if column.is_primary_key:
            return column
    raise ValueError(f"表 {columns_list[0].table_name} 未找到主键")


def build_entity_content(table_name: str, columns: list[ColumnMeta]) -> str:
    class_name = to_camel(table_name, upper=True)
    table_comment = normalize_comment(columns[0].table_comment, table_name)
    imports = [
        str(CONFIG["entity_type_annotation_import"]),
        *CONFIG["entity_extra_imports"],
    ]
    java_types = {column.java_type for column in columns}
    if "BigDecimal" in java_types:
        imports.append("java.math.BigDecimal")
    if "LocalDate" in java_types:
        imports.append("java.time.LocalDate")
    if "LocalDateTime" in java_types:
        imports.append("java.time.LocalDateTime")
    import_lines = [f"import {item};" for item in unique_preserve_order(imports)]
    lines = [
        f"package {resolve_entity_package()};",
        "",
        *import_lines,
        "",
        "/**",
        f" * {table_comment}",
        " *",
        f" * @author {CONFIG['author']}",
        f" * @date {detect_date_text()}",
        " */",
        *CONFIG["entity_extra_annotations"],
        annotation_line(
            str(CONFIG["entity_type_annotation"]),
            str(CONFIG["entity_type_description_attr"]),
            table_comment,
        ),
        f"public class {class_name} " + "{",
    ]
    for column in columns:
        column_comment = normalize_comment(column.column_comment, column.column_name)
        lines.extend(
            [
                "    /**",
                f"     * {column_comment}",
                "     */",
                "    " + annotation_line(
                    str(CONFIG["entity_field_annotation"]),
                    str(CONFIG["entity_field_description_attr"]),
                    column_comment,
                ),
                f"    private {column.java_type} {column.field_name};",
                "",
            ]
        )
    if lines[-1] == "":
        lines.pop()
    lines.append("}")
    return "\n".join(lines) + "\n"


def build_mapper_content(table_name: str, columns: list[ColumnMeta]) -> str:
    class_name = to_camel(table_name, upper=True)
    mapper_name = f"{class_name}Mapper"
    table_comment = normalize_comment(columns[0].table_comment, table_name)
    pk = find_primary_key(columns)
    lines = [
        f"package {resolve_mapper_package()};",
        "",
        f"import {resolve_entity_package()}.{class_name};",
        "import org.apache.ibatis.annotations.Param;",
        "",
        "import java.util.List;",
        "",
        "/**",
        f" * 定义{table_comment}的 MyBatis Mapper。",
        " *",
        f" * @author {CONFIG['author']}",
        f" * @date {detect_date_text()}",
        " */",
        f"public interface {mapper_name} " + "{",
        "    /**",
        f"     * 新增{table_comment}记录。",
        "     *",
        f"     * @param entity 待新增的{table_comment}实体",
        "     * @return 受影响的记录条数",
        "     */",
        f"    int insert({class_name} entity);",
        "",
        "    /**",
        f"     * 根据主键查询单条{table_comment}记录。",
        "     *",
        f"     * @param {pk.field_name} 主键ID",
        f"     * @return 匹配的{table_comment}实体；不存在时返回 null",
        "     */",
        f"    {class_name} selectById(@Param(\"{pk.field_name}\") {pk.java_type} {pk.field_name});",
        "",
        "    /**",
        f"     * 按条件查询{table_comment}列表。",
        "     *",
        "     * @param entity 查询条件；仅使用非空字段参与过滤",
        f"     * @return 匹配的{table_comment}列表",
        "     */",
        f"    List<{class_name}> selectByCondition({class_name} entity);",
        "",
        "    /**",
        f"     * 按条件统计{table_comment}数量。",
        "     *",
        "     * @param entity 统计条件；仅使用非空字段参与过滤",
        "     * @return 匹配的记录数量",
        "     */",
        f"    Long countByCondition({class_name} entity);",
        "",
        "    /**",
        f"     * 根据主键更新{table_comment}记录。",
        "     *",
        "     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段",
        "     * @return 受影响的记录条数",
        "     */",
        f"    int updateById({class_name} entity);",
        "",
        "    /**",
        f"     * 根据主键删除{table_comment}记录。",
        "     *",
        f"     * @param {pk.field_name} 主键ID",
        "     * @return 受影响的记录条数",
        "     */",
        f"    int deleteById(@Param(\"{pk.field_name}\") {pk.java_type} {pk.field_name});",
        "}",
    ]
    return "\n".join(lines) + "\n"


def build_mapper_xml_content(table_name: str, columns: list[ColumnMeta]) -> str:
    class_name = to_camel(table_name, upper=True)
    mapper_name = f"{class_name}Mapper"
    pk = find_primary_key(columns)
    insert_columns = [column for column in columns if not column.is_auto_increment and not column.is_stored_generated]
    update_columns = [column for column in columns if not column.is_primary_key and not column.is_stored_generated]

    result_map_lines = []
    for column in columns:
        tag = "id" if column.is_primary_key else "result"
        result_map_lines.append(f"        <{tag} column=\"{column.column_name}\" property=\"{column.field_name}\"/>")

    condition_lines = []
    for column in columns:
        condition_lines.extend(
            [
                f"            <if test=\"{xml_test_expression(column)}\">",
                f"                AND {column.column_name} = #{{{column.field_name}}}",
                "            </if>",
            ]
        )

    insert_column_lines = []
    insert_value_lines = []
    for column in insert_columns:
        insert_column_lines.extend(
            [
                f"            <if test=\"{xml_test_expression(column)}\">",
                f"                {column.column_name},",
                "            </if>",
            ]
        )
        insert_value_lines.extend(
            [
                f"            <if test=\"{xml_test_expression(column)}\">",
                f"                #{{{column.field_name}}},",
                "            </if>",
            ]
        )

    update_set_lines = []
    for column in update_columns:
        update_set_lines.extend(
            [
                f"            <if test=\"{xml_test_expression(column)}\">",
                f"                {column.column_name} = #{{{column.field_name}}},",
                "            </if>",
            ]
        )

    column_list = ",\n        ".join(column.column_name for column in columns)
    lines = [
        "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
        "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">",
        f"<mapper namespace=\"{resolve_mapper_package()}.{mapper_name}\">",
        "",
        "    <!-- 映射数据库字段与实体属性 -->",
        f"    <resultMap id=\"{class_name}ResultMap\" type=\"{resolve_entity_package()}.{class_name}\">",
        *result_map_lines,
        "    </resultMap>",
        "",
        "    <!-- 复用基础查询列 -->",
        "    <sql id=\"Base_Column_List\">",
        f"        {column_list}",
        "    </sql>",
        "",
        "    <!-- 根据主键查询单条记录 -->",
        f"    <select id=\"selectById\" resultMap=\"{class_name}ResultMap\">",
        "        SELECT",
        "        <include refid=\"Base_Column_List\"/>",
        f"        FROM {table_name}",
        f"        WHERE {pk.column_name} = #{{{pk.field_name}}}",
        "    </select>",
        "",
        "    <!-- 按非空条件查询记录列表 -->",
        f"    <select id=\"selectByCondition\" resultMap=\"{class_name}ResultMap\">",
        "        SELECT",
        "        <include refid=\"Base_Column_List\"/>",
        f"        FROM {table_name}",
        "        <where>",
        *condition_lines,
        "        </where>",
        "    </select>",
        "",
        "    <!-- 按非空条件统计记录数量 -->",
        "    <select id=\"countByCondition\" resultType=\"java.lang.Long\">",
        "        SELECT COUNT(*)",
        f"        FROM {table_name}",
        "        <where>",
        *condition_lines,
        "        </where>",
        "    </select>",
        "",
        "    <!-- 动态新增非空字段，主键回填到实体 -->",
        f"    <insert id=\"insert\" parameterType=\"{resolve_entity_package()}.{class_name}\" useGeneratedKeys=\"true\" keyProperty=\"{pk.field_name}\">",
        f"        INSERT INTO {table_name}",
        "        <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">",
        *insert_column_lines,
        "        </trim>",
        "        <trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">",
        *insert_value_lines,
        "        </trim>",
        "    </insert>",
        "",
        "    <!-- 根据主键动态更新非空字段 -->",
        f"    <update id=\"updateById\" parameterType=\"{resolve_entity_package()}.{class_name}\">",
        f"        UPDATE {table_name}",
        "        <trim prefix=\"SET\" suffixOverrides=\",\">",
        *update_set_lines,
        "        </trim>",
        f"        WHERE {pk.column_name} = #{{{pk.field_name}}}",
        "    </update>",
        "",
        "    <!-- 根据主键删除单条记录 -->",
        "    <delete id=\"deleteById\">",
        f"        DELETE FROM {table_name}",
        f"        WHERE {pk.column_name} = #{{{pk.field_name}}}",
        "    </delete>",
        "</mapper>",
    ]
    return "\n".join(lines) + "\n"


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def main() -> None:
    args = parse_args()
    apply_cli_overrides(args)
    metadata = load_metadata()
    if not metadata:
        raise SystemExit("没有匹配到任何表")

    entity_output_dir = resolve_entity_output_dir()
    mapper_output_dir = resolve_mapper_output_dir()
    xml_output_dir = resolve_xml_output_dir()

    for table_name, columns in metadata.items():
        class_name = to_camel(table_name, upper=True)
        mapper_name = f"{class_name}Mapper"
        write_file(entity_output_dir / f"{class_name}.java", build_entity_content(table_name, columns))
        write_file(mapper_output_dir / f"{mapper_name}.java", build_mapper_content(table_name, columns))
        write_file(xml_output_dir / f"{mapper_name}.xml", build_mapper_xml_content(table_name, columns))

    print(f"已生成 {len(metadata)} 张表的代码")
    print(f"Entity 输出目录：{entity_output_dir}")
    print(f"Mapper 输出目录：{mapper_output_dir}")
    print(f"XML 输出目录：{xml_output_dir}")


if __name__ == "__main__":
    main()
