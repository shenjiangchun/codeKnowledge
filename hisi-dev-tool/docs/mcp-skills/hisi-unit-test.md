# HiSi 单元测试生成 Skill

## 目的
为指定方法生成高质量的单元测试

## 使用场景
用户提供类名或方法名，需要生成对应的JUnit测试

## 步骤
1. 使用 `kg_method_detail` 获取目标方法的完整信息（签名、注解、所属类）
2. 使用 `kg_callers` 了解该方法被谁调用（推断使用场景）
3. 使用 `kg_callees` 了解该方法调用了哪些依赖（需要mock的对象）
4. 分析方法签名确定参数类型和返回值
5. 生成测试代码

## 测试模板
- JUnit 5 + Mockito
- 使用 @ExtendWith(MockitoExtension.class)
- 覆盖：正常路径、边界条件、异常场景
- 使用 AssertJ 风格断言

## 输出格式
- 完整的Java测试类代码
- 包含 @DisplayName 描述
- Mock所有外部依赖
- 至少3个测试方法（正常/边界/异常）
