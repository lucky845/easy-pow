package io.github.lucky845.basic.pow;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 只有当容器里还没有"任何 PowStorage 名称"时才生效——按名称判定,不解析类型。
 *
 * <p>不用 {@code @ConditionalOnMissingBean}:它按<b>能否解析出某个 PowStorage Bean</b> 判定,
 * 于是把用户的自定义实现、以及 Redis 自动配置里别名引用的另一个 PowStorage 都当成"已存在",
 * 结果随求值顺序漂移(曾导致 Redis 存储静默不生效)。按名称判定不触发类型解析,结果稳定。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@org.springframework.context.annotation.Conditional(ConditionalOnMissingDefaultStorage.MissingDefaultStorage.class)
public @interface ConditionalOnMissingDefaultStorage {

    /** 条件实现内部使用,不要直接引用。 */
    class MissingDefaultStorage extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String[] names = context.getBeanFactory() == null
                    ? new String[0]
                    : context.getBeanFactory().getBeanNamesForType(PowStorage.class, true, false);
            String[] existing = names.clone();
            // Spring 的条件求值用的是 ConfigurationCondition+REGISTER_BEAN 阶段,而自动配置在解析阶段
            // 顺序执行;此时用户用 @Bean 方法声明的 PowStorage 可能还没进 BeanDefinitionRegistry,
            // 只能从注册表里找定义。漏掉这一步会让默认存储抢在用户实现之前注册。
            if (existing.length == 0 && context.getRegistry() != null) {
                org.springframework.beans.factory.support.BeanDefinitionRegistry registry =
                        (org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getRegistry();
                java.util.List<String> declared = new java.util.ArrayList<>();
                for (String beanName : registry.getBeanDefinitionNames()) {
                    if (declaresPowStorage(registry, beanName)) {
                        declared.add(beanName);
                    }
                }
                existing = declared.toArray(String[]::new);
            }
            if (existing.length == 0) {
                return ConditionOutcome.match("容器里还没有任何 PowStorage");
            }
            return ConditionOutcome.noMatch("已存在 PowStorage: " + String.join(",", existing));
        }

        /** BeanDefinition 里声明的类型(可能是工厂方法返回类型)是不是 PowStorage;解析不出来就当不是。 */
        private static boolean declaresPowStorage(org.springframework.beans.factory.support.BeanDefinitionRegistry registry,
                                                  String beanName) {
            try {
                Class<?> type = registry.getBeanDefinition(beanName).getResolvableType().resolve();
                return type != null && PowStorage.class.isAssignableFrom(type);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
