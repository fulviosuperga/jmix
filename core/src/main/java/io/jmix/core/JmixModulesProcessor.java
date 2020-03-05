/*
 * Copyright 2019 Haulmont.
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

package io.jmix.core;

import io.jmix.core.annotation.JmixModule;
import io.jmix.core.annotation.JmixProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JmixModulesProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(JmixModulesProcessor.class);
    private Environment environment;
    private JmixModules jmixModules;

    public JmixModules getJmixModules() {
        return jmixModules;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        List<JmixModuleDescriptor> modules = new ArrayList<>();
        List<String> moduleIds = new ArrayList<>();

        List<JmixModuleDescriptor> leafModules = new ArrayList<>();

        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition beanDefinition = registry.getBeanDefinition(beanName);
            if (!(beanDefinition instanceof AnnotatedBeanDefinition)
                    || ((AnnotatedBeanDefinition) beanDefinition).getFactoryMethodMetadata() != null) {
                continue;
            }
            AnnotationMetadata annotationMetadata = ((AnnotatedBeanDefinition) beanDefinition).getMetadata();
            if (!(annotationMetadata.hasAnnotation(JmixModule.class.getName())
                    || annotationMetadata.hasAnnotation("org.springframework.boot.autoconfigure.SpringBootApplication")
                    || annotationMetadata.hasAnnotation("org.springframework.boot.autoconfigure.EnableAutoConfiguration"))) {
                    continue;
            }
            String beanClassName = beanDefinition.getBeanClassName();

            ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();
            if (beanClassLoader == null) {
                throw new RuntimeException("BeanClassLoader is null");
            }
            Class<?> beanClass;
            try {
                beanClass = beanClassLoader.loadClass(beanClassName);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            JmixModule moduleAnnotation = AnnotationUtils.findAnnotation(beanClass, JmixModule.class);
            String moduleId = getModuleId(moduleAnnotation, beanClass);
            if (!moduleIds.contains(moduleId)) {
                moduleIds.add(moduleId);
            }

            JmixModuleDescriptor module = modules.stream()
                    .filter(descriptor -> descriptor.getId().equals(moduleId))
                    .findAny()
                    .orElseGet(() -> {
                        JmixModuleDescriptor descriptor = new JmixModuleDescriptor(moduleId);
                        load(descriptor, moduleAnnotation, modules);
                        return descriptor;
                    });
            if (!modules.contains(module))
                modules.add(module);

            if (isDependingOnAll(moduleAnnotation)) {
                leafModules.add(module);
            }
        }

        for (JmixModuleDescriptor leafModule : leafModules) {
            for (JmixModuleDescriptor module : modules) {
                if (!leafModules.contains(module)) {
                    leafModule.addDependency(module);
                }
            }
        }

        modules.sort((c1, c2) -> {
            int res = c1.compareTo(c2);
            if (res != 0)
                return res;
            else
                return moduleIds.indexOf(c1.getId()) - moduleIds.indexOf(c2.getId());
        });

        log.info("Using Jmix modules: {}", modules);

        jmixModules = new JmixModules(modules);

        if (environment instanceof ConfigurableEnvironment) {
            MutablePropertySources sources = ((ConfigurableEnvironment) environment).getPropertySources();
            sources.addLast(new JmixPropertySource(jmixModules));
        } else {
            throw new IllegalStateException("Not a ConfigurableEnvironment, cannot register JmixModules property source");
        }

    }

    private boolean isDependingOnAll(@Nullable JmixModule moduleAnnotation) {
        return moduleAnnotation == null
                || (moduleAnnotation.dependsOn().length == 1 && moduleAnnotation.dependsOn()[0] == JmixModule.AllModules.class);
    }

    private String getModuleId(@Nullable JmixModule jmixModule, Class<?> aClass) {
        String moduleId = "";
        if (jmixModule != null) {
            moduleId = jmixModule.id();
        }
        if ("".equals(moduleId)) {
            moduleId = aClass.getPackage().getName();
        }
        return moduleId;
    }

    private void load(JmixModuleDescriptor module, @Nullable JmixModule moduleAnnotation,
                      List<JmixModuleDescriptor> modules) {
        if (!isDependingOnAll(moduleAnnotation)) {
            for (Class<?> depClass : moduleAnnotation.dependsOn()) {
                JmixModule depModuleAnnotation = AnnotationUtils.findAnnotation(depClass, JmixModule.class);
                if (depModuleAnnotation == null) {
                    log.warn("Dependency class {} is not annotated with {}, ignoring it", depClass.getName(), JmixModule.class.getName());
                    continue;
                }
                String depModuleId = getModuleId(depModuleAnnotation, depClass);

                JmixModuleDescriptor depModule = modules.stream()
                        .filter(descriptor -> descriptor.getId().equals(depModuleId))
                        .findAny()
                        .orElseGet(() -> {
                            JmixModuleDescriptor descriptor = new JmixModuleDescriptor(depModuleId);
                            load(descriptor, depModuleAnnotation, modules);
                            modules.add(descriptor);
                            return descriptor;
                        });
                module.addDependency(depModule);
            }
        }

        if (moduleAnnotation != null) {
            for (JmixProperty propertyAnn : moduleAnnotation.properties()) {
                module.setProperty(propertyAnn.name(), propertyAnn.value(), propertyAnn.append());
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1000; // to be before ConfigurationClassPostProcessor
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    private static class JmixPropertySource extends EnumerablePropertySource<JmixModules> {

        public JmixPropertySource(JmixModules source) {
            super("JmixModules properties", source);
        }

        @Nonnull
        @Override
        public String[] getPropertyNames() {
            Set<String> propertyNames = new HashSet<>();
            for (JmixModuleDescriptor module : source.getAll()) {
                propertyNames.addAll(module.getPropertyNames());
            }
            return propertyNames.toArray(new String[0]);
        }

        @Override
        public Object getProperty(String name) {
            return source.getProperty(name);
        }
    }

}
