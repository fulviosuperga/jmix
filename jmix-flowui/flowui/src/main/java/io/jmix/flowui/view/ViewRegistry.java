package io.jmix.flowui.view;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.router.RouterLayout;
import io.jmix.core.ClassManager;
import io.jmix.core.ExtendedEntities;
import io.jmix.core.Metadata;
import io.jmix.core.Resources;
import io.jmix.core.impl.scanning.AnnotationScanMetadataReaderFactory;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.FlowuiProperties;
import io.jmix.flowui.exception.NoSuchViewException;
import io.jmix.flowui.sys.ViewControllerDefinition;
import io.jmix.flowui.sys.ViewControllerMeta;
import io.jmix.flowui.sys.ViewControllersConfiguration;
import io.jmix.flowui.sys.ViewDescriptorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.jmix.core.common.util.Preconditions.checkNotEmptyString;

@Component("flowui_ViewRegistry")
public class ViewRegistry implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ViewRegistry.class);

    public static final Pattern ENTITY_VIEW_PATTERN = Pattern.compile("([A-Za-z0-9]+[$_][A-Z][_A-Za-z0-9]*)\\..+");

    public static final String DETAIL_VIEW_SUFFIX = ".detail";
    public static final String LIST_VIEW_SUFFIX = ".list";
    public static final String LOOKUP_VIEW_SUFFIX = ".lookup";

    protected Metadata metadata;
    protected Resources resources;
    protected ClassManager classManager;
    protected FlowuiProperties properties;
    protected ExtendedEntities extendedEntities;
    protected ApplicationContext applicationContext;
    protected AnnotationScanMetadataReaderFactory metadataReaderFactory;

    protected Map<String, ViewInfo> views = new HashMap<>();

    protected Map<Class<?>, ViewInfo> primaryDetailViews = new HashMap<>();
    protected Map<Class<?>, ViewInfo> primaryLookupViews = new HashMap<>();

    protected List<ViewControllersConfiguration> configurations = Collections.emptyList();

    protected volatile boolean initialized;

    protected RouteConfiguration routeConfiguration;

    protected ReadWriteLock lock = new ReentrantReadWriteLock();

    @Autowired
    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    @Autowired
    public void setResources(Resources resources) {
        this.resources = resources;
    }

    @Autowired
    public void setClassManager(ClassManager classManager) {
        this.classManager = classManager;
    }

    @Autowired
    public void setProperties(FlowuiProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setExtendedEntities(ExtendedEntities extendedEntities) {
        this.extendedEntities = extendedEntities;
    }

    @Autowired
    public void setMetadataReaderFactory(AnnotationScanMetadataReaderFactory metadataReaderFactory) {
        this.metadataReaderFactory = metadataReaderFactory;
    }

    @Autowired(required = false)
    public void setConfigurations(List<ViewControllersConfiguration> configurations) {
        this.configurations = configurations;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Make the registry to reload views on next request.
     */
    public void reset() {
        initialized = false;
    }

    protected void checkInitialized() {
        if (!initialized) {
            lock.readLock().unlock();
            lock.writeLock().lock();
            try {
                if (!initialized) {
                    init();
                    initialized = true;
                }
            } finally {
                lock.readLock().lock();
                lock.writeLock().unlock();
            }
        }
    }

    protected void init() {
        long startTime = System.currentTimeMillis();

        views.clear();
        primaryDetailViews.clear();
        primaryLookupViews.clear();

        loadViewConfigurations();

        log.info("{} initialized in {} ms", getClass().getSimpleName(), System.currentTimeMillis() - startTime);
    }

    protected void loadViewConfigurations() {
        for (ViewControllersConfiguration provider : configurations) {
            List<ViewControllerDefinition> viewControllers = provider.getViewControllers();

            Map<String, String> projectViews = new HashMap<>(viewControllers.size());

            for (ViewControllerDefinition definition : viewControllers) {
                String viewId = definition.getId();
                String controllerClassName = definition.getControllerClassName();

                String existingViewController = projectViews.get(viewId);
                if (existingViewController != null
                        && !Objects.equals(existingViewController, controllerClassName)) {
                    throw new RuntimeException(
                            String.format("Project contains views with the same id: '%s'. See '%s' and '%s'",
                                    viewId,
                                    controllerClassName,
                                    existingViewController));
                } else {
                    projectViews.put(viewId, controllerClassName);
                }

                Class<? extends View<?>> controllerClass = loadDefinedViewClass(controllerClassName);
                String templatePath = ViewDescriptorUtils.resolveTemplatePath(controllerClass);
                ViewInfo viewInfo = new ViewInfo(viewId, controllerClassName, controllerClass, templatePath);

                registerView(viewId, viewInfo);
            }

            projectViews.clear();
        }
    }

    protected void registerView(String id, ViewInfo viewInfo) {
        String controllerClassName = viewInfo.getControllerClassName();
        MetadataReader classMetadata = loadClassMetadata(controllerClassName);
        AnnotationMetadata annotationMetadata = classMetadata.getAnnotationMetadata();

        registerPrimaryDetailView(viewInfo, annotationMetadata);
        registerPrimaryLookupView(viewInfo, annotationMetadata);

        views.put(id, viewInfo);
    }

    protected void registerPrimaryDetailView(ViewInfo viewInfo, AnnotationMetadata annotationMetadata) {
        getAnnotationValue(annotationMetadata, PrimaryDetailView.class)
                .ifPresent(aClass ->
                        primaryDetailViews.put(aClass, viewInfo));
    }

    protected void registerPrimaryLookupView(ViewInfo viewInfo, AnnotationMetadata annotationMetadata) {
        getAnnotationValue(annotationMetadata, PrimaryLookupView.class)
                .ifPresent(aClass ->
                        primaryLookupViews.put(aClass, viewInfo));
    }

    protected Optional<Class<?>> getAnnotationValue(AnnotationMetadata annotationMetadata,
                                                    Class<?> annotationClass) {
        Map<String, Object> annotation =
                annotationMetadata.getAnnotationAttributes(annotationClass.getName());

        if (annotation != null) {
            Class<?> entityClass = (Class<?>) annotation.get("value");
            if (entityClass != null) {
                MetaClass metaClass = metadata.getClass(entityClass);
                MetaClass originalMetaClass = extendedEntities.getOriginalOrThisMetaClass(metaClass);
                return Optional.of(originalMetaClass.getJavaClass());
            }
        }

        return Optional.empty();
    }

    protected MetadataReader loadClassMetadata(String className) {
        Resource resource = resources.getResource("/" + className.replace(".", "/") + ".class");
        if (!resource.isReadable()) {
            throw new RuntimeException(String.format("Resource %s is not readable for class %s", resource, className));
        }
        try {
            return metadataReaderFactory.getMetadataReader(resource);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read resource " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected Class<? extends View<?>> loadDefinedViewClass(String className) {
        checkNotEmptyString(className, "class name is empty");
        return (Class<? extends View<?>>) classManager.loadClass(className);
    }

    /**
     * Returns view information by id.
     *
     * @param id view id as set in the {@link ViewController} annotation
     * @return view's registration information
     */
    public Optional<ViewInfo> findViewInfo(String id) {
        lock.readLock().lock();
        try {
            checkInitialized();

            ViewInfo viewInfo = views.get(id);
            if (viewInfo != null) {
                return Optional.of(viewInfo);
            }

            Matcher matcher = ENTITY_VIEW_PATTERN.matcher(id);
            if (matcher.matches()) {
                MetaClass metaClass = metadata.findClass(matcher.group(1));
                if (metaClass == null) {
                    return Optional.empty();
                }

                MetaClass originalMetaClass = extendedEntities.getOriginalMetaClass(metaClass);
                if (originalMetaClass != null) {
                    String originalId = new StringBuilder(id)
                            .replace(matcher.start(1), matcher.end(1), originalMetaClass.getName()).toString();
                    viewInfo = views.get(originalId);
                }
            }

            return Optional.ofNullable(viewInfo);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns view information by id.
     *
     * @param id view id as set in the {@link ViewController} annotation
     * @return view's registration information
     * @throws NoSuchViewException if the view with specified id is not registered
     */
    public ViewInfo getViewInfo(String id) {
        Optional<ViewInfo> info = findViewInfo(id);
        if (info.isPresent()) {
            return info.get();
        }

        throw new NoSuchViewException(id);
    }

    /**
     * @return {@code true} if the registry contains a view with provided id
     */
    public boolean hasView(String id) {
        return findViewInfo(id).isPresent();
    }

    public Collection<ViewInfo> getViewInfos() {
        lock.readLock().lock();
        try {
            checkInitialized();
            return views.values();
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getMetaClassViewId(MetaClass metaClass, String suffix) {
        MetaClass viewMetaClass = metaClass;
        MetaClass originalMetaClass = extendedEntities.getOriginalMetaClass(metaClass);
        if (originalMetaClass != null) {
            viewMetaClass = originalMetaClass;
        }

        return viewMetaClass.getName() + suffix;
    }

    public String getListViewId(MetaClass metaClass) {
        return getMetaClassViewId(metaClass, LIST_VIEW_SUFFIX);
    }

    public String getLookupViewId(MetaClass metaClass) {
        MetaClass originalMetaClass = extendedEntities.getOriginalOrThisMetaClass(metaClass);
        ViewInfo viewInfo = primaryLookupViews.get(originalMetaClass.getJavaClass());
        if (viewInfo != null) {
            return viewInfo.getId();
        }

        return getMetaClassViewId(metaClass, LOOKUP_VIEW_SUFFIX);
    }

    public String getDetailViewId(MetaClass metaClass) {
        MetaClass originalMetaClass = extendedEntities.getOriginalOrThisMetaClass(metaClass);
        ViewInfo viewInfo = primaryDetailViews.get(originalMetaClass.getJavaClass());
        if (viewInfo != null) {
            return viewInfo.getId();
        }

        return getMetaClassViewId(metaClass, DETAIL_VIEW_SUFFIX);
    }

    public ViewInfo getDetailViewInfo(MetaClass metaClass) {
        MetaClass originalMetaClass = extendedEntities.getOriginalOrThisMetaClass(metaClass);
        ViewInfo viewInfo = primaryDetailViews.get(originalMetaClass.getJavaClass());
        if (viewInfo != null) {
            return viewInfo;
        }

        String detailViewId = getDetailViewId(metaClass);
        return getViewInfo(detailViewId);
    }

    public ViewInfo getDetailViewInfo(Class<?> entityClass) {
        MetaClass metaClass = metadata.getSession().getClass(entityClass);
        return getDetailViewInfo(metaClass);
    }

    public ViewInfo getDetailViewInfo(Object entity) {
        MetaClass metaClass = metadata.getClass(entity);
        return getDetailViewInfo(metaClass);
    }

    public ViewInfo getLookupViewInfo(MetaClass metaClass) {
        MetaClass originalMetaClass = extendedEntities.getOriginalOrThisMetaClass(metaClass);
        ViewInfo viewInfo = primaryLookupViews.get(originalMetaClass.getJavaClass());
        if (viewInfo != null) {
            return viewInfo;
        }

        String lookupViewId = getAvailableLookupViewId(metaClass);
        return getViewInfo(lookupViewId);
    }

    /**
     * Get available lookup view by class of entity
     *
     * @param entityClass entity class
     * @return id of lookup view
     * @throws NoSuchViewException if the view with specified ID is not registered
     */
    public ViewInfo getLookupViewInfo(Class<?> entityClass) {
        MetaClass metaClass = metadata.getSession().getClass(entityClass);
        return getLookupViewInfo(metaClass);
    }

    public ViewInfo getLookupViewInfo(Object entity) {
        MetaClass metaClass = metadata.getClass(entity);
        return getLookupViewInfo(metaClass);
    }

    public String getAvailableLookupViewId(MetaClass metaClass) {
        String id = getLookupViewId(metaClass);
        if (!hasView(id)) {
            id = getListViewId(metaClass);
        }

        return id;
    }

    @SuppressWarnings({"unchecked"})
    public void loadViewClass(String className) {
        Class<? extends View<?>> viewClass = ((Class<? extends View<?>>) classManager.loadClass(className));

        ViewControllerMeta controllerMeta = new ViewControllerMeta(metadataReaderFactory, viewClass);

        ViewControllerDefinition viewControllerDefinition = new ViewControllerDefinition(
                controllerMeta.getId(), controllerMeta.getControllerClass(), controllerMeta.getResource());

        ViewControllersConfiguration controllersConfiguration =
                new ViewControllersConfiguration(applicationContext, metadataReaderFactory);

        controllersConfiguration.setExplicitDefinitions(Collections.singletonList(viewControllerDefinition));

        configurations.add(controllersConfiguration);

        registerRoute(viewClass);

        reset();
    }

    /**
     * Iterates over all registered views and register their routes if needed.
     * Replaces route registration in case a newer controller class is available.
     */
    public void registerViewRoutes() {
        for (ViewInfo viewInfo : getViewInfos()) {
            registerRoute(viewInfo);
        }
    }

    /**
     * Registers route for the passed viewInfo instance if needed. Replaces
     * route registration in case a newer controller class is available.
     *
     * @param viewInfo a viewInfo instance to register route
     */
    public void registerRoute(ViewInfo viewInfo) {
        registerRoute(viewInfo.getControllerClass());
    }

    /**
     * Registers route for the passed controller class if needed. Replaces
     * route registration in case a newer controller class is available.
     *
     * @param controllerClass a controller class to register route
     */
    public void registerRoute(Class<? extends View<?>> controllerClass) {
        Route route = controllerClass.getAnnotation(Route.class);
        if (route == null) {
            return;
        }

        RouteConfiguration routeConfiguration = getRouteConfiguration();
        if (routeConfiguration.isRouteRegistered(controllerClass)) {
            log.debug("Skipping route '{}' for class '{}' since it was already registered",
                    route.value(), controllerClass.getName());
            return;
        }

        // Controller class can be hot-deployed, thus route path
        // was registered for prev version of class
        if (routeConfiguration.isPathAvailable(route.value())) {
            routeConfiguration.removeRoute(route.value());
        }

        routeConfiguration.setRoute(route.value(), controllerClass,
                getParentChain(route, getDefaultParentChain()));
    }

    protected List<Class<? extends RouterLayout>> getParentChain(Route route,
                                                                 List<Class<? extends RouterLayout>> defaultChain) {
        Class<? extends RouterLayout> layout = route.layout();
        if (DefaultMainViewParent.class.isAssignableFrom(layout)) {
            return defaultChain;
        }

        // UI is the default route parent, so no need to add it explicitly
        return UI.class == layout ? Collections.emptyList() : List.of(layout);
    }

    @SuppressWarnings("unchecked")
    protected List<Class<? extends RouterLayout>> getDefaultParentChain() {
        Optional<ViewInfo> mainViewInfo = findViewInfo(properties.getMainViewId());
        if (mainViewInfo.isPresent()) {
            Class<? extends View<?>> controllerClass = mainViewInfo.get().getControllerClass();
            if (RouterLayout.class.isAssignableFrom(controllerClass)) {
                return List.of(((Class<? extends RouterLayout>) controllerClass));
            }
        }

        return Collections.emptyList();
    }

    public RouteConfiguration getRouteConfiguration() {
        if (routeConfiguration == null) {
            throw new IllegalStateException(RouteConfiguration.class.getSimpleName() + " isn't initialized");
        }

        return routeConfiguration;
    }

    public void setRouteConfiguration(RouteConfiguration routeConfiguration) {
        this.routeConfiguration = routeConfiguration;
    }
}
