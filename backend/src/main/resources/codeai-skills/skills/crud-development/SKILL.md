# CRUD 开发规范技能

## 技能概述

本技能提供 CRUD（Create, Read, Update, Delete）开发的标准规范和最佳实践，帮助开发者快速生成符合项目标准的后端代码。

## 适用场景

- 新增业务模块的 CRUD 接口开发
- 标准化管理后台的数据维护功能
- RESTful API 的规范化开发
- 数据库表的增删改查操作封装

## 技能能力

### 1. 代码生成

支持生成以下层次的代码：

- **Controller 层**：RESTful API 接口定义
- **Service 层**：业务逻辑处理
- **Mapper 层**：数据库操作
- **Entity 层**：实体类定义
- **DTO 层**：数据传输对象
- **VO 层**：视图对象

### 2. 规范检查

- 命名规范检查
- 注释完整性检查
- 参数校验规范检查
- 异常处理规范检查

### 3. 最佳实践建议

- 分页查询优化建议
- 批量操作优化建议
- 事务处理建议
- 缓存策略建议

## 使用方法

### 基本用法

```
请帮我为用户管理模块生成 CRUD 代码，表名：sys_user
```

### 指定选项

```
请为订单表生成 CRUD 代码，要求：
1. 包含分页查询
2. 支持批量删除
3. 包含数据导出功能
```

### 代码审查

```
请检查以下 Controller 代码是否符合 CRUD 规范：
[粘贴代码]
```

## 代码规范

### Controller 层规范

```java
@RestController
@RequestMapping("/api/v1/{module}")
@Tag(name = "{模块名称}管理", description = "{模块名称}相关接口")
public class {Entity}Controller {

    @Autowired
    private {Entity}Service {entity}Service;

    @PostMapping
    @Operation(summary = "创建{实体}")
    public Result<{Entity}VO> create(@Valid @RequestBody {Entity}CreateDTO dto) {
        return Result.success({entity}Service.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新{实体}")
    public Result<{Entity}VO> update(@PathVariable Long id, @Valid @RequestBody {Entity}UpdateDTO dto) {
        return Result.success({entity}Service.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除{实体}")
    public Result<Void> delete(@PathVariable Long id) {
        {entity}Service.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取{实体}详情")
    public Result<{Entity}VO> getById(@PathVariable Long id) {
        return Result.success({entity}Service.getById(id));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询{实体}列表")
    public Result<PageResult<{Entity}VO>> page({Entity}QueryDTO query) {
        return Result.success({entity}Service.page(query));
    }
}
```

### Service 层规范

```java
@Service
@Slf4j
public class {Entity}ServiceImpl implements {Entity}Service {

    @Autowired
    private {Entity}Mapper {entity}Mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public {Entity}VO create({Entity}CreateDTO dto) {
        // 参数校验
        validateCreate(dto);

        // 实体转换
        {Entity} entity = convertToEntity(dto);

        // 保存
        {entity}Mapper.insert(entity);

        log.info("创建{实体}成功, id={}", entity.getId());
        return convertToVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public {Entity}VO update(Long id, {Entity}UpdateDTO dto) {
        // 存在性检查
        {Entity} entity = checkExists(id);

        // 更新字段
        updateEntity(entity, dto);

        // 保存
        {entity}Mapper.updateById(entity);

        log.info("更新{实体}成功, id={}", id);
        return convertToVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 存在性检查
        checkExists(id);

        // 逻辑删除
        {entity}Mapper.deleteById(id);

        log.info("删除{实体}成功, id={}", id);
    }

    @Override
    public {Entity}VO getById(Long id) {
        {Entity} entity = checkExists(id);
        return convertToVO(entity);
    }

    @Override
    public PageResult<{Entity}VO> page({Entity}QueryDTO query) {
        Page<{Entity}> page = {entity}Mapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()),
            buildQueryWrapper(query)
        );
        return PageResult.of(page.convert(this::convertToVO));
    }

    // 私有方法省略...
}
```

### Mapper 层规范

```java
@Mapper
public interface {Entity}Mapper extends BaseMapper<{Entity}> {

    /**
     * 自定义查询方法示例
     */
    @Select("SELECT * FROM {table_name} WHERE status = #{status} ORDER BY create_time DESC")
    List<{Entity}> selectByStatus(@Param("status") Integer status);
}
```

## 命名规范

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 实体类 | 大驼峰，与表名对应 | SysUser |
| DTO | 实体名 + DTO | SysUserCreateDTO |
| VO | 实体名 + VO | SysUserVO |
| Service | 实体名 + Service | SysUserService |
| Mapper | 实体名 + Mapper | SysUserMapper |
| Controller | 实体名 + Controller | SysUserController |

## 注意事项

1. 所有数据库操作必须有事务注解
2. 删除操作优先使用逻辑删除
3. 查询列表必须有分页
4. 敏感字段必须脱敏处理
5. 接口必须有权限控制

## 版本历史

- v1.0.0 (2026-04-13): 初始版本，包含基础 CRUD 代码生成能力