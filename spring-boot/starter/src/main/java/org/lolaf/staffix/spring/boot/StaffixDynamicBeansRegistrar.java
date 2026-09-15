/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.spring.boot;

import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.application.factories.spring.SpringApplicationFactorySettings;
import org.lolaf.staffix.spring.boot.mapper.AcceptorPropsMapper;
import org.lolaf.staffix.spring.boot.mapper.InitiatorPropsMapper;
import org.lolaf.staffix.spring.boot.props.AcceptorProps;
import org.lolaf.staffix.spring.boot.props.InitiatorProps;
import org.lolaf.staffix.spring.boot.props.StaffixProperties;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Registers one {@link FixAcceptor} and one {@link FixInitiator} Spring bean per entry in
 * {@code staffix.acceptors} / {@code staffix.initiators}. Bean names follow the pattern
 * {@code fixAcceptor-<key>} and {@code fixInitiator-<key>}.
 *
 * <p>Each bean depends on {@code fixEngine} so it is created after the engine is started, and
 * destroyed before it on context close. The registered destroy method is {@code stop} (the
 * no-arg overload exposed by both {@link FixAcceptor} and {@link FixInitiator}).
 */
public class StaffixDynamicBeansRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final String FIX_ENGINE = "fixEngine";
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        StaffixProperties props = Binder.get(environment)
                .bind("staffix", StaffixProperties.class)
                .orElseGet(StaffixProperties::new);

        if (!props.isEnabled()) {
            return;
        }
        ConfigurableListableBeanFactory beanFactory = (ConfigurableListableBeanFactory) registry;

        for (Map.Entry<String, AcceptorProps> e : props.getAcceptors().entrySet()) {
            registerAcceptor(registry, beanFactory, e.getKey(), e.getValue());
        }
        for (Map.Entry<String, InitiatorProps> e : props.getInitiators().entrySet()) {
            registerInitiator(registry, beanFactory, e.getKey(), e.getValue());
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // No-op: all definitions registered in postProcessBeanDefinitionRegistry.
    }

    private void registerAcceptor(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory beanFactory, String key, AcceptorProps props) {
        RootBeanDefinition def = new RootBeanDefinition(FixAcceptor.class);
        def.setInstanceSupplier(() -> {
            FixEngine engine = beanFactory.getBean(FIX_ENGINE, FixEngine.class);
            ScheduledExecutorService scheduler = props.getSchedulerBean() != null ? beanFactory.getBean(props.getSchedulerBean(), ScheduledExecutorService.class) : null;
            ApplicationContext ctx = beanFactory.getBean(SpringApplicationFactorySettings.class).getApplicationContext();
            return engine.newAcceptor(AcceptorPropsMapper.build(key, props, scheduler, ctx)).start();
        });
        def.setDestroyMethodName("stop");
        def.setDependsOn(FIX_ENGINE);
        def.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        registry.registerBeanDefinition("fixAcceptor-" + key, def);
    }

    private void registerInitiator(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory beanFactory, String key, InitiatorProps props) {
        RootBeanDefinition def = new RootBeanDefinition(FixInitiator.class);
        def.setInstanceSupplier(() -> {
            FixEngine engine = beanFactory.getBean(FIX_ENGINE, FixEngine.class);
            ScheduledExecutorService scheduler = props.getSchedulerBean() != null ? beanFactory.getBean(props.getSchedulerBean(), ScheduledExecutorService.class) : null;
            ApplicationContext ctx = beanFactory.getBean(SpringApplicationFactorySettings.class).getApplicationContext();
            return engine.newInitiator(InitiatorPropsMapper.build(key, props, scheduler, ctx)).start();
        });
        def.setDestroyMethodName("stop");
        def.setDependsOn(FIX_ENGINE);
        def.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        registry.registerBeanDefinition("fixInitiator-" + key, def);
    }
}
