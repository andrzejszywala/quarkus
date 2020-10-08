package io.quarkus.rest.deployment.processor;

import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.DELETE;
import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.GET;
import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.HEAD;
import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.OPTIONS;
import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.PATCH;
import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.POST;
import static io.quarkus.rest.deployment.framework.QuarkusRestDotNames.PUT;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.ws.rs.BeanParam;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.AutoInjectAnnotationBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CapabilityBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.deployment.util.JandexUtil;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.rest.deployment.framework.AdditionalReaders;
import io.quarkus.rest.deployment.framework.AdditionalWriters;
import io.quarkus.rest.deployment.framework.EndpointIndexer;
import io.quarkus.rest.deployment.framework.QuarkusRestDotNames;
import io.quarkus.rest.runtime.QuarkusRestConfig;
import io.quarkus.rest.runtime.QuarkusRestInitialiser;
import io.quarkus.rest.runtime.QuarkusRestRecorder;
import io.quarkus.rest.runtime.core.ContextResolvers;
import io.quarkus.rest.runtime.core.DynamicFeatures;
import io.quarkus.rest.runtime.core.ExceptionMapping;
import io.quarkus.rest.runtime.core.Features;
import io.quarkus.rest.runtime.core.GenericTypeMapping;
import io.quarkus.rest.runtime.core.ParamConverterProviders;
import io.quarkus.rest.runtime.core.QuarkusRestDeployment;
import io.quarkus.rest.runtime.core.Serialisers;
import io.quarkus.rest.runtime.core.Serialisers.BuiltinReader;
import io.quarkus.rest.runtime.core.Serialisers.BuiltinWriter;
import io.quarkus.rest.runtime.injection.ContextProducers;
import io.quarkus.rest.runtime.model.InjectableBean;
import io.quarkus.rest.runtime.model.MethodParameter;
import io.quarkus.rest.runtime.model.ParameterType;
import io.quarkus.rest.runtime.model.ResourceClass;
import io.quarkus.rest.runtime.model.ResourceContextResolver;
import io.quarkus.rest.runtime.model.ResourceDynamicFeature;
import io.quarkus.rest.runtime.model.ResourceExceptionMapper;
import io.quarkus.rest.runtime.model.ResourceFeature;
import io.quarkus.rest.runtime.model.ResourceInterceptors;
import io.quarkus.rest.runtime.model.ResourceMethod;
import io.quarkus.rest.runtime.model.ResourceParamConverterProvider;
import io.quarkus.rest.runtime.model.ResourceReader;
import io.quarkus.rest.runtime.model.ResourceReaderInterceptor;
import io.quarkus.rest.runtime.model.ResourceRequestInterceptor;
import io.quarkus.rest.runtime.model.ResourceResponseInterceptor;
import io.quarkus.rest.runtime.model.ResourceWriter;
import io.quarkus.rest.runtime.model.ResourceWriterInterceptor;
import io.quarkus.rest.runtime.model.RestClientInterface;
import io.quarkus.rest.runtime.providers.serialisers.VertxJsonMessageBodyWriter;
import io.quarkus.rest.runtime.providers.serialisers.jsonb.JsonbMessageBodyReader;
import io.quarkus.rest.runtime.providers.serialisers.jsonb.JsonbMessageBodyWriter;
import io.quarkus.rest.runtime.spi.BeanFactory;
import io.quarkus.rest.spi.ContainerRequestFilterBuildItem;
import io.quarkus.rest.spi.ContainerResponseFilterBuildItem;
import io.quarkus.rest.spi.DynamicFeatureBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.BasicRoute;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class QuarkusRestProcessor {

    private static final String QUARKUS_INIT_CLASS = "io.quarkus.rest.runtime.__QuarkusInit";
    private static Map<DotName, String> BUILTIN_HTTP_ANNOTATIONS_TO_METHOD = new HashMap<>();

    static {
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(GET, "GET");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(POST, "POST");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(HEAD, "HEAD");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(PUT, "PUT");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(DELETE, "DELETE");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(PATCH, "PATCH");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD.put(OPTIONS, "OPTIONS");
        BUILTIN_HTTP_ANNOTATIONS_TO_METHOD = Collections.unmodifiableMap(BUILTIN_HTTP_ANNOTATIONS_TO_METHOD);
    }

    @BuildStep
    public FeatureBuildItem buildSetup() {
        return new FeatureBuildItem(Feature.QUARKUS_REST);
    }

    @BuildStep
    CapabilityBuildItem capability() {
        return new CapabilityBuildItem(Capability.QUARKUS_REST);
    }

    @BuildStep
    AutoInjectAnnotationBuildItem contextInjection(
            BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer) {
        additionalBeanBuildItemBuildProducer
                .produce(AdditionalBeanBuildItem.builder().addBeanClasses(ContextProducers.class).build());
        return new AutoInjectAnnotationBuildItem(DotName.createSimple(Context.class.getName()),
                DotName.createSimple(BeanParam.class.getName()));

    }

    @BuildStep
    void scanResources(
            // TODO: We need to use this index instead of BeanArchiveIndexBuildItem to avoid build cycles. It it OK?
            CombinedIndexBuildItem combinedIndexBuildItem,
            BuildProducer<AnnotationsTransformerBuildItem> annotationsTransformerBuildItemBuildProducer,
            BuildProducer<ResourceScanningResultBuildItem> resourceScanningResultBuildItemBuildProducer) {
        IndexView index = combinedIndexBuildItem.getIndex();
        Collection<AnnotationInstance> paths = index.getAnnotations(QuarkusRestDotNames.PATH);

        Collection<AnnotationInstance> allPaths = new ArrayList<>(paths);

        if (allPaths.isEmpty()) {
            // no detected @Path, bail out
            return;
        }

        Map<DotName, ClassInfo> scannedResources = new HashMap<>();
        Map<DotName, String> scannedResourcePaths = new HashMap<>();
        Map<DotName, ClassInfo> possibleSubResources = new HashMap<>();
        Map<DotName, String> pathInterfaces = new HashMap<>();
        Map<DotName, MethodInfo> resourcesThatNeedCustomProducer = new HashMap<>();
        Set<String> beanParams = new HashSet<>();

        for (AnnotationInstance beanParamAnnotation : index.getAnnotations(QuarkusRestDotNames.BEAN_PARAM)) {
            AnnotationTarget target = beanParamAnnotation.target();
            // FIXME: this isn't right wrt generics
            switch (target.kind()) {
                case FIELD:
                    beanParams.add(target.asField().type().toString());
                    break;
                case METHOD:
                    Type setterParamType = target.asMethod().parameters().get(0);
                    beanParams.add(setterParamType.toString());
                    break;
                case METHOD_PARAMETER:
                    MethodInfo method = target.asMethodParameter().method();
                    int paramIndex = target.asMethodParameter().position();
                    Type paramType = method.parameters().get(paramIndex);
                    beanParams.add(paramType.toString());
                    break;
                default:
                    break;
            }
        }

        for (AnnotationInstance annotation : allPaths) {
            if (annotation.target().kind() == AnnotationTarget.Kind.CLASS) {
                ClassInfo clazz = annotation.target().asClass();
                if (!Modifier.isInterface(clazz.flags())) {
                    scannedResources.put(clazz.name(), clazz);
                    scannedResourcePaths.put(clazz.name(), annotation.value().asString());
                } else {
                    pathInterfaces.put(clazz.name(), annotation.value().asString());
                }
                MethodInfo ctor = hasJaxRsCtorParams(clazz);
                if (ctor != null) {
                    resourcesThatNeedCustomProducer.put(clazz.name(), ctor);
                }
            }
        }

        if (!resourcesThatNeedCustomProducer.isEmpty()) {
            annotationsTransformerBuildItemBuildProducer
                    .produce(new AnnotationsTransformerBuildItem(
                            new VetoingAnnotationTransformer(resourcesThatNeedCustomProducer.keySet())));
        }

        for (Map.Entry<DotName, String> i : pathInterfaces.entrySet()) {
            for (ClassInfo clazz : index.getAllKnownImplementors(i.getKey())) {
                if (!Modifier.isAbstract(clazz.flags())) {
                    if ((clazz.enclosingClass() == null || Modifier.isStatic(clazz.flags())) &&
                            clazz.enclosingMethod() == null) {
                        if (!scannedResources.containsKey(clazz.name())) {
                            scannedResources.put(clazz.name(), clazz);
                            scannedResourcePaths.put(clazz.name(), i.getValue());
                        }
                    }
                }
            }
        }

        resourceScanningResultBuildItemBuildProducer.produce(new ResourceScanningResultBuildItem(scannedResources,
                scannedResourcePaths, possibleSubResources, pathInterfaces, resourcesThatNeedCustomProducer, beanParams));
    }

    @BuildStep
    void generateCustomProducer(Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
            BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer) {
        if (!resourceScanningResultBuildItem.isPresent()) {
            return;
        }

        Map<DotName, MethodInfo> resourcesThatNeedCustomProducer = resourceScanningResultBuildItem.get()
                .getResourcesThatNeedCustomProducer();
        Set<String> beanParams = resourceScanningResultBuildItem.get()
                .getBeanParams();
        if (!resourcesThatNeedCustomProducer.isEmpty() || !beanParams.isEmpty()) {
            CustomResourceProducersGenerator.generate(resourcesThatNeedCustomProducer, beanParams,
                    generatedBeanBuildItemBuildProducer,
                    additionalBeanBuildItemBuildProducer);
        }
    }

    @BuildStep
    void additionalBeans(Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> additionalBean) {
        if (capabilities.isPresent(Capability.JACKSON)) {
            additionalBean.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(VertxJsonMessageBodyWriter.class.getName())
                    .setUnremovable().build());
        }
        // make these beans to they can get instantiated with the Quarkus CDI configured Jsonb object
        if (capabilities.isPresent(Capability.JSONB)) {
            additionalBean.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClass(JsonbMessageBodyReader.class.getName())
                    .addBeanClass(JsonbMessageBodyWriter.class.getName())
                    .setUnremovable().build());
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    public void setupEndpoints(BeanArchiveIndexBuildItem beanArchiveIndexBuildItem,
            BeanContainerBuildItem beanContainerBuildItem,
            QuarkusRestConfig config,
            Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer,
            BuildProducer<BytecodeTransformerBuildItem> bytecodeTransformerBuildItemBuildProducer,
            QuarkusRestRecorder recorder,
            RecorderContext recorderContext,
            ShutdownContextBuildItem shutdownContext,
            HttpBuildTimeConfig vertxConfig,
            Capabilities capabilities,
            List<ContainerRequestFilterBuildItem> additionalContainerRequestFilters,
            List<ContainerResponseFilterBuildItem> additionalContainerResponseFilters,
            List<DynamicFeatureBuildItem> additionalDynamicFeatures,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<RouteBuildItem> routes) {

        if (!resourceScanningResultBuildItem.isPresent()) {
            // no detected @Path, bail out
            return;
        }

        IndexView index = beanArchiveIndexBuildItem.getIndex();
        Collection<ClassInfo> applications = index
                .getAllKnownSubclasses(QuarkusRestDotNames.APPLICATION);
        Collection<ClassInfo> containerRequestFilters = index
                .getAllKnownImplementors(QuarkusRestDotNames.CONTAINER_REQUEST_FILTER);
        Collection<ClassInfo> containerResponseFilters = index
                .getAllKnownImplementors(QuarkusRestDotNames.CONTAINER_RESPONSE_FILTER);
        Collection<ClassInfo> readerInterceptors = index
                .getAllKnownImplementors(QuarkusRestDotNames.READER_INTERCEPTOR);
        Collection<ClassInfo> writerInterceptors = index
                .getAllKnownImplementors(QuarkusRestDotNames.WRITER_INTERCEPTOR);
        Collection<ClassInfo> exceptionMappers = index
                .getAllKnownImplementors(QuarkusRestDotNames.EXCEPTION_MAPPER);
        Collection<ClassInfo> writers = index
                .getAllKnownImplementors(QuarkusRestDotNames.MESSAGE_BODY_WRITER);
        Collection<ClassInfo> readers = index
                .getAllKnownImplementors(QuarkusRestDotNames.MESSAGE_BODY_READER);
        Collection<ClassInfo> contextResolvers = index
                .getAllKnownImplementors(QuarkusRestDotNames.CONTEXT_RESOLVER);
        Collection<ClassInfo> features = index
                .getAllKnownImplementors(QuarkusRestDotNames.FEATURE);
        Collection<ClassInfo> paramConverterProviders = index
                .getAllKnownImplementors(QuarkusRestDotNames.PARAM_CONVERTER_PROVIDER);
        Collection<ClassInfo> dynamicFeatures = index
                .getAllKnownImplementors(QuarkusRestDotNames.DYNAMIC_FEATURE);
        Collection<ClassInfo> invocationCallbacks = index
                .getAllKnownImplementors(QuarkusRestDotNames.INVOCATION_CALLBACK);

        Map<DotName, ClassInfo> scannedResources = resourceScanningResultBuildItem.get().getScannedResources();
        Map<DotName, String> scannedResourcePaths = resourceScanningResultBuildItem.get().getScannedResourcePaths();
        Map<DotName, ClassInfo> possibleSubResources = resourceScanningResultBuildItem.get().getPossibleSubResources();
        Map<DotName, String> pathInterfaces = resourceScanningResultBuildItem.get().getPathInterfaces();

        Map<DotName, String> httpAnnotationToMethod = new HashMap<>(BUILTIN_HTTP_ANNOTATIONS_TO_METHOD);
        Collection<AnnotationInstance> httpMethodInstances = index.getAnnotations(QuarkusRestDotNames.HTTP_METHOD);
        for (AnnotationInstance httpMethodInstance : httpMethodInstances) {
            if (httpMethodInstance.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            httpAnnotationToMethod.put(httpMethodInstance.target().asClass().name(), httpMethodInstance.value().asString());
        }

        Set<String> allowedClasses = new HashSet<>();
        boolean filterClasses = false;
        Application application = null;
        for (ClassInfo applicationClassInfo : applications) {
            // FIXME: yell if there's more than one
            String applicationClass = applicationClassInfo.name().toString();
            try {
                Class<?> appClass = Thread.currentThread().getContextClassLoader().loadClass(applicationClass);
                application = (Application) appClass.newInstance();
                for (Class<?> klass : application.getClasses()) {
                    allowedClasses.add(klass.getName());
                }
                filterClasses = true;
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        ParamConverterProviders converterProviders = new ParamConverterProviders();
        for (ClassInfo converterClass : paramConverterProviders) {
            if (keepProvider(converterClass, filterClasses, allowedClasses)) {
                ResourceParamConverterProvider converter = new ResourceParamConverterProvider();
                converter.setFactory(recorder.factory(converterClass.name().toString(),
                        beanContainerBuildItem.getValue()));
                AnnotationInstance priorityInstance = converterClass.classAnnotation(QuarkusRestDotNames.PRIORITY);
                if (priorityInstance != null) {
                    converter.setPriority(priorityInstance.value().asInt());
                }
                converterProviders.addParamConverterProviders(converter);
            }
        }
        converterProviders.sort();

        Map<String, String> existingConverters = new HashMap<>();
        List<ResourceClass> resourceClasses = new ArrayList<>();
        List<ResourceClass> subResourceClasses = new ArrayList<>();
        AdditionalReaders additionalReaders = new AdditionalReaders();
        AdditionalWriters additionalWriters = new AdditionalWriters();
        Map<String, InjectableBean> injectableBeans = new HashMap<>();
        EndpointIndexer endpointIndexer;

        try (ClassCreator c = new ClassCreator(new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true),
                QUARKUS_INIT_CLASS, null, Object.class.getName(), QuarkusRestInitialiser.class.getName());
                MethodCreator initConverters = c.getMethodCreator("init", void.class, QuarkusRestDeployment.class)) {

            endpointIndexer = new EndpointIndexer.Builder()
                    .setIndex(index)
                    .setBeanContainer(beanContainerBuildItem.getValue())
                    .setGeneratedClassBuildItemBuildProducer(generatedClassBuildItemBuildProducer)
                    .setBytecodeTransformerBuildItemBuildProducer(bytecodeTransformerBuildItemBuildProducer)
                    .setRecorder(recorder)
                    .setExistingConverters(existingConverters).setScannedResourcePaths(scannedResourcePaths).setConfig(config)
                    .setAdditionalReaders(additionalReaders).setHttpAnnotationToMethod(httpAnnotationToMethod)
                    .setInjectableBeans(injectableBeans).setAdditionalWriters(additionalWriters)
                    .setHasRuntimeConverters(!converterProviders.getParamConverterProviders().isEmpty())
                    .setInitConverters(initConverters).build();

            for (ClassInfo i : scannedResources.values()) {
                ResourceClass endpoints = endpointIndexer.createEndpoints(i);
                if (endpoints != null) {
                    resourceClasses.add(endpoints);
                }
            }

            List<RestClientInterface> clientDefinitions = new ArrayList<>();
            for (Map.Entry<DotName, String> i : pathInterfaces.entrySet()) {
                ClassInfo clazz = index.getClassByName(i.getKey());
                //these interfaces can also be clients
                //so we generate client proxies for them
                RestClientInterface clientProxy = endpointIndexer.createClientProxy(clazz,
                        bytecodeTransformerBuildItemBuildProducer,
                        i.getValue());
                if (clientProxy != null) {
                    clientDefinitions.add(clientProxy);
                }
            }
            Map<String, RuntimeValue<Function<WebTarget, ?>>> clientImplementations = generateClientInvokers(recorderContext,
                    clientDefinitions, generatedClassBuildItemBuildProducer);

            //now index possible sub resources. These are all classes that have method annotations
            //that are not annotated @Path
            Deque<ClassInfo> toScan = new ArrayDeque<>();
            for (DotName methodAnnotation : httpAnnotationToMethod.keySet()) {
                for (AnnotationInstance instance : index.getAnnotations(methodAnnotation)) {
                    MethodInfo method = instance.target().asMethod();
                    ClassInfo classInfo = method.declaringClass();
                    toScan.add(classInfo);
                }
            }
            while (!toScan.isEmpty()) {
                ClassInfo classInfo = toScan.poll();
                if (scannedResources.containsKey(classInfo.name()) ||
                        pathInterfaces.containsKey(classInfo.name()) ||
                        possibleSubResources.containsKey(classInfo.name())) {
                    continue;
                }
                possibleSubResources.put(classInfo.name(), classInfo);
                ResourceClass endpoints = endpointIndexer.createEndpoints(classInfo);
                if (endpoints != null) {
                    subResourceClasses.add(endpoints);
                }
                //we need to also look for all sub classes and interfaces
                //they may have type variables that need to be handled
                toScan.addAll(index.getKnownDirectImplementors(classInfo.name()));
                toScan.addAll(index.getKnownDirectSubclasses(classInfo.name()));
            }

            ResourceInterceptors interceptors = new ResourceInterceptors();
            for (ClassInfo filterClass : containerRequestFilters) {
                if (keepProvider(filterClass, filterClasses, allowedClasses)) {
                    ResourceRequestInterceptor interceptor = new ResourceRequestInterceptor();
                    interceptor.setFactory(recorder.factory(filterClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    interceptor.setPreMatching(filterClass.classAnnotation(QuarkusRestDotNames.PRE_MATCHING) != null);
                    if (interceptor.isPreMatching()) {
                        interceptors.addResourcePreMatchInterceptor(interceptor);
                    } else {
                        Set<String> nameBindingNames = endpointIndexer.nameBindingNames(filterClass);
                        if (nameBindingNames.isEmpty()) {
                            interceptors.addGlobalRequestInterceptor(interceptor);
                        } else {
                            interceptor.setNameBindingNames(nameBindingNames);
                            interceptors.addNameRequestInterceptor(interceptor);
                        }
                    }
                    AnnotationInstance priorityInstance = filterClass.classAnnotation(QuarkusRestDotNames.PRIORITY);
                    if (priorityInstance != null) {
                        interceptor.setPriority(priorityInstance.value().asInt());
                    }
                }
            }
            for (ContainerRequestFilterBuildItem additionalFilter : additionalContainerRequestFilters) {
                ResourceRequestInterceptor interceptor = new ResourceRequestInterceptor();
                interceptor.setFactory(recorder.factory(additionalFilter.getClassName(), beanContainerBuildItem.getValue()));
                if (additionalFilter.getPriority() != null) {
                    interceptor.setPriority(additionalFilter.getPriority());
                }
                if (additionalFilter.getPreMatching() != null) {
                    interceptor.setPreMatching(additionalFilter.getPreMatching());
                    if (additionalFilter.getPreMatching()) {
                        interceptors.addResourcePreMatchInterceptor(interceptor);
                    } else {
                        interceptors.addGlobalRequestInterceptor(interceptor);
                    }
                } else {
                    interceptors.addGlobalRequestInterceptor(interceptor);
                }
            }

            for (ClassInfo filterClass : containerResponseFilters) {
                if (keepProvider(filterClass, filterClasses, allowedClasses)) {
                    ResourceResponseInterceptor interceptor = new ResourceResponseInterceptor();
                    interceptor.setFactory(recorder.factory(filterClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    Set<String> nameBindingNames = endpointIndexer.nameBindingNames(filterClass);
                    if (nameBindingNames.isEmpty()) {
                        interceptors.addGlobalResponseInterceptor(interceptor);
                    } else {
                        interceptor.setNameBindingNames(nameBindingNames);
                        interceptors.addNameResponseInterceptor(interceptor);
                    }
                    AnnotationInstance priorityInstance = filterClass.classAnnotation(QuarkusRestDotNames.PRIORITY);
                    if (priorityInstance != null) {
                        interceptor.setPriority(priorityInstance.value().asInt());
                    }
                }
            }
            for (ContainerResponseFilterBuildItem additionalFilter : additionalContainerResponseFilters) {
                ResourceResponseInterceptor interceptor = new ResourceResponseInterceptor();
                interceptor.setFactory(recorder.factory(additionalFilter.getClassName(), beanContainerBuildItem.getValue()));
                if (additionalFilter.getPriority() != null) {
                    interceptor.setPriority(additionalFilter.getPriority());
                }
                interceptors.addGlobalResponseInterceptor(interceptor);
            }

            for (ClassInfo filterClass : writerInterceptors) {
                if (keepProvider(filterClass, filterClasses, allowedClasses)) {
                    ResourceWriterInterceptor interceptor = new ResourceWriterInterceptor();
                    interceptor.setFactory(recorder.factory(filterClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    Set<String> nameBindingNames = endpointIndexer.nameBindingNames(filterClass);
                    if (nameBindingNames.isEmpty()) {
                        interceptors.addGlobalWriterInterceptor(interceptor);
                    } else {
                        interceptor.setNameBindingNames(nameBindingNames);
                        interceptors.addNameWriterInterceptor(interceptor);
                    }
                    AnnotationInstance priorityInstance = filterClass.classAnnotation(QuarkusRestDotNames.PRIORITY);
                    if (priorityInstance != null) {
                        interceptor.setPriority(priorityInstance.value().asInt());
                    }
                }
            }
            for (ClassInfo filterClass : readerInterceptors) {
                if (keepProvider(filterClass, filterClasses, allowedClasses)) {
                    ResourceReaderInterceptor interceptor = new ResourceReaderInterceptor();
                    interceptor.setFactory(recorder.factory(filterClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    Set<String> nameBindingNames = endpointIndexer.nameBindingNames(filterClass);
                    if (nameBindingNames.isEmpty()) {
                        interceptors.addGlobalReaderInterceptor(interceptor);
                    } else {
                        interceptor.setNameBindingNames(nameBindingNames);
                        interceptors.addNameReaderInterceptor(interceptor);
                    }
                    AnnotationInstance priorityInstance = filterClass.classAnnotation(QuarkusRestDotNames.PRIORITY);
                    if (priorityInstance != null) {
                        interceptor.setPriority(priorityInstance.value().asInt());
                    }
                }
            }

            ExceptionMapping exceptionMapping = new ExceptionMapping();
            Map<DotName, ResourceExceptionMapper<Throwable>> handledExceptionToHigherPriorityMapper = new HashMap();
            for (ClassInfo mapperClass : exceptionMappers) {
                if (keepProvider(mapperClass, filterClasses, allowedClasses)) {
                    List<Type> typeParameters = JandexUtil.resolveTypeParameters(mapperClass.name(),
                            QuarkusRestDotNames.EXCEPTION_MAPPER,
                            index);
                    ResourceExceptionMapper<Throwable> mapper = new ResourceExceptionMapper<>();
                    mapper.setFactory(recorder.factory(mapperClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    AnnotationInstance priorityInstance = mapperClass.classAnnotation(QuarkusRestDotNames.PRIORITY);
                    if (priorityInstance != null) {
                        mapper.setPriority(priorityInstance.value().asInt());
                    }
                    DotName handledExceptionDotName = typeParameters.get(0).name();
                    if (handledExceptionToHigherPriorityMapper.containsKey(handledExceptionDotName)) {
                        if (mapper.getPriority() < handledExceptionToHigherPriorityMapper.get(handledExceptionDotName)
                                .getPriority()) {
                            handledExceptionToHigherPriorityMapper.put(handledExceptionDotName, mapper);
                        }
                    } else {
                        handledExceptionToHigherPriorityMapper.put(handledExceptionDotName, mapper);
                    }
                }
            }
            for (Map.Entry<DotName, ResourceExceptionMapper<Throwable>> entry : handledExceptionToHigherPriorityMapper
                    .entrySet()) {
                recorder.registerExceptionMapper(exceptionMapping, entry.getKey().toString(), entry.getValue());
            }

            ContextResolvers ctxResolvers = new ContextResolvers();
            for (ClassInfo resolverClass : contextResolvers) {
                if (keepProvider(resolverClass, filterClasses, allowedClasses)) {
                    List<Type> typeParameters = JandexUtil.resolveTypeParameters(resolverClass.name(),
                            QuarkusRestDotNames.CONTEXT_RESOLVER,
                            index);
                    ResourceContextResolver resolver = new ResourceContextResolver();
                    resolver.setFactory(recorder.factory(resolverClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    resolver.setMediaTypeStrings(getProducesMediaTypes(resolverClass));
                    recorder.registerContextResolver(ctxResolvers, typeParameters.get(0).name().toString(), resolver);
                }
            }

            Features feats = new Features();
            for (ClassInfo featureClass : features) {
                if (keepProvider(featureClass, filterClasses, allowedClasses)) {
                    ResourceFeature resourceFeature = new ResourceFeature();
                    resourceFeature.setFactory(recorder.factory(featureClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    feats.addFeature(resourceFeature);
                }
            }

            DynamicFeatures dynamicFeats = new DynamicFeatures();
            for (ClassInfo dynamicFeatureClass : dynamicFeatures) {
                if (keepProvider(dynamicFeatureClass, filterClasses, allowedClasses)) {
                    ResourceDynamicFeature resourceFeature = new ResourceDynamicFeature();
                    resourceFeature.setFactory(recorder.factory(dynamicFeatureClass.name().toString(),
                            beanContainerBuildItem.getValue()));
                    dynamicFeats.addFeature(resourceFeature);
                }
            }
            for (DynamicFeatureBuildItem additionalDynamicFeature : additionalDynamicFeatures) {
                ResourceDynamicFeature resourceFeature = new ResourceDynamicFeature();
                resourceFeature.setFactory(
                        recorder.factory(additionalDynamicFeature.getClassName(), beanContainerBuildItem.getValue()));
                dynamicFeats.addFeature(resourceFeature);
            }

            GenericTypeMapping genericTypeMapping = new GenericTypeMapping();
            for (ClassInfo invocationCallback : invocationCallbacks) {
                try {
                    List<Type> typeParameters = JandexUtil.resolveTypeParameters(invocationCallback.name(),
                            QuarkusRestDotNames.INVOCATION_CALLBACK, index);
                    recorder.registerInvocationHandlerGenericType(genericTypeMapping, invocationCallback.name().toString(),
                            typeParameters.get(0).name().toString());
                } catch (Exception ignored) {

                }
            }

            Serialisers serialisers = new Serialisers();
            for (ClassInfo writerClass : writers) {
                if (keepProvider(writerClass, filterClasses, allowedClasses)) {
                    ResourceWriter writer = new ResourceWriter();
                    writer.setBuiltin(false);
                    AnnotationInstance producesAnnotation = writerClass.classAnnotation(QuarkusRestDotNames.PRODUCES);
                    if (producesAnnotation != null) {
                        writer.setMediaTypeStrings(Arrays.asList(producesAnnotation.value().asStringArray()));
                    }
                    List<Type> typeParameters = JandexUtil.resolveTypeParameters(writerClass.name(),
                            QuarkusRestDotNames.MESSAGE_BODY_WRITER,
                            index);
                    String writerClassName = writerClass.name().toString();
                    writer.setFactory(recorder.factory(writerClassName, beanContainerBuildItem.getValue()));
                    AnnotationInstance constrainedToInstance = writerClass.classAnnotation(QuarkusRestDotNames.CONSTRAINED_TO);
                    if (constrainedToInstance != null) {
                        writer.setConstraint(RuntimeType.valueOf(constrainedToInstance.value().asEnum()));
                    }
                    recorder.registerWriter(serialisers, typeParameters.get(0).name().toString(), writer);
                    reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, writerClassName));
                }
            }
            for (ClassInfo readerClass : readers) {
                if (keepProvider(readerClass, filterClasses, allowedClasses)) {
                    List<Type> typeParameters = JandexUtil.resolveTypeParameters(readerClass.name(),
                            QuarkusRestDotNames.MESSAGE_BODY_READER,
                            index);
                    ResourceReader reader = new ResourceReader();
                    reader.setBuiltin(false);
                    String readerClassName = readerClass.name().toString();
                    reader.setFactory(recorder.factory(readerClassName,
                            beanContainerBuildItem.getValue()));
                    AnnotationInstance consumesAnnotation = readerClass.classAnnotation(QuarkusRestDotNames.CONSUMES);
                    if (consumesAnnotation != null) {
                        reader.setMediaTypeStrings(Arrays.asList(consumesAnnotation.value().asStringArray()));
                    }
                    AnnotationInstance constrainedToInstance = readerClass.classAnnotation(QuarkusRestDotNames.CONSTRAINED_TO);
                    if (constrainedToInstance != null) {
                        reader.setConstraint(RuntimeType.valueOf(constrainedToInstance.value().asEnum()));
                    }
                    recorder.registerReader(serialisers, typeParameters.get(0).name().toString(), reader);
                    reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, readerClassName));
                }
            }

            // additional readers / writers based on capabilities

            if (capabilities.isPresent(Capability.JACKSON)) {
                // TODO: this is probably not the right thing to do as Vertx doesn't the Quarkus CDI configured ObjectMapper here
                registerWriter(recorder, serialisers, Object.class, VertxJsonMessageBodyWriter.class,
                        beanContainerBuildItem.getValue(), MediaType.APPLICATION_JSON);
            }
            if (capabilities.isPresent(Capability.JSONB)) {
                registerReader(recorder, serialisers, Object.class, JsonbMessageBodyReader.class,
                        beanContainerBuildItem.getValue(), MediaType.APPLICATION_JSON, null);
                registerWriter(recorder, serialisers, Object.class, JsonbMessageBodyWriter.class,
                        beanContainerBuildItem.getValue(), MediaType.APPLICATION_JSON);
            }

            // built-ins

            for (BuiltinWriter builtinWriter : Serialisers.BUILTIN_WRITERS) {
                registerWriter(recorder, serialisers, builtinWriter.entityClass, builtinWriter.writerClass,
                        beanContainerBuildItem.getValue(),
                        builtinWriter.mediaType);
                reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, builtinWriter.writerClass.getName()));
            }
            for (BuiltinReader builtinReader : Serialisers.BUILTIN_READERS) {
                registerReader(recorder, serialisers, builtinReader.entityClass, builtinReader.readerClass,
                        beanContainerBuildItem.getValue(),
                        builtinReader.mediaType, builtinReader.constraint);
                reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, builtinReader.readerClass.getName()));
            }

            for (AdditionalReaders.Entry additionalReader : additionalReaders.get()) {
                Class readerClass = additionalReader.getReaderClass();
                registerReader(recorder, serialisers, additionalReader.getEntityClass(), readerClass,
                        beanContainerBuildItem.getValue(), additionalReader.getMediaType(), additionalReader.getConstraint());
                reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, readerClass.getName()));
            }

            for (AdditionalWriters.Entry<?> entry : additionalWriters.get()) {
                Class<? extends MessageBodyWriter<?>> writerClass = entry.getWriterClass();
                registerWriter(recorder, serialisers, entry.getEntityClass(), writerClass,
                        beanContainerBuildItem.getValue(), entry.getMediaType());
                reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, writerClass.getName()));
            }

            initConverters.returnValue(null);
            BeanFactory<QuarkusRestInitialiser> initClassFactory = recorder.factory(QUARKUS_INIT_CLASS,
                    beanContainerBuildItem.getValue());

            String applicationPath = determineApplicationPath(index);

            // Handler used for both the default and non-default deployment path (specified as application path or resteasyConfig.path)
            // Routes use the order VertxHttpRecorder.DEFAULT_ROUTE_ORDER + 1 to ensure the default route is called before the resteasy one
            Handler<RoutingContext> handler = recorder.handler(interceptors.sort(), exceptionMapping, ctxResolvers, feats,
                    dynamicFeats,
                    serialisers, resourceClasses, subResourceClasses,
                    beanContainerBuildItem.getValue(), shutdownContext, config, vertxConfig, applicationPath,
                    clientImplementations,
                    genericTypeMapping, converterProviders, initClassFactory, application);

            String deploymentPath = sanitizeApplicationPath(applicationPath);
            // Exact match for resources matched to the root path
            routes.produce(new RouteBuildItem(
                    new BasicRoute(deploymentPath, VertxHttpRecorder.DEFAULT_ROUTE_ORDER + 1), handler));
            String matchPath = deploymentPath;
            if (matchPath.endsWith("/")) {
                matchPath += "*";
            } else {
                matchPath += "/*";
            }
            // Match paths that begin with the deployment path
            routes.produce(new RouteBuildItem(new BasicRoute(matchPath, VertxHttpRecorder.DEFAULT_ROUTE_ORDER + 1), handler));
        }
    }

    private boolean keepProvider(ClassInfo providerClass, boolean filterClasses, Set<String> allowedClasses) {
        return providerClass.classAnnotation(QuarkusRestDotNames.PROVIDER) != null
                && (!filterClasses || allowedClasses.contains(providerClass.name().toString()));
    }

    private String determineApplicationPath(IndexView index) {
        Collection<AnnotationInstance> applicationPaths = index.getAnnotations(QuarkusRestDotNames.APPLICATION_PATH);
        if (applicationPaths.isEmpty()) {
            return null;
        }
        // currently we only examine the first class that is annotated with @ApplicationPath so best
        // fail if the user code has multiple such annotations instead of surprising the user
        // at runtime
        if (applicationPaths.size() > 1) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (AnnotationInstance annotationInstance : applicationPaths) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",");
                }
                sb.append(annotationInstance.target().asClass().name().toString());
            }
            throw new RuntimeException("Multiple classes ( " + sb.toString()
                    + ") have been annotated with @ApplicationPath which is currently not supported");
        }
        String applicationPath = null;
        AnnotationValue applicationPathValue = applicationPaths.iterator().next().value();
        if ((applicationPathValue != null)) {
            applicationPath = applicationPathValue.asString();
        }
        return applicationPath;
    }

    private String sanitizeApplicationPath(String applicationPath) {
        if ((applicationPath == null) || applicationPath.isEmpty()) {
            return "/";
        }
        applicationPath = applicationPath.trim();
        if (applicationPath.equals("/"))
            applicationPath = "";
        // add leading slash
        if (!applicationPath.startsWith("/"))
            applicationPath = "/" + applicationPath;
        // remove trailing slash
        if (applicationPath.endsWith("/"))
            applicationPath = applicationPath.substring(0, applicationPath.length() - 1);
        return applicationPath;
    }

    private void registerWriter(QuarkusRestRecorder recorder, Serialisers serialisers, Class<?> entityClass,
            Class<? extends MessageBodyWriter<?>> writerClass, BeanContainer beanContainer,
            String mediaType) {
        ResourceWriter writer = new ResourceWriter();
        writer.setFactory(recorder.factory(writerClass.getName(), beanContainer));
        writer.setMediaTypeStrings(Collections.singletonList(mediaType));
        recorder.registerWriter(serialisers, entityClass.getName(), writer);
    }

    private void registerReader(QuarkusRestRecorder recorder, Serialisers serialisers, Class<?> entityClass,
            Class<? extends MessageBodyReader<?>> readerClass, BeanContainer beanContainer, String mediaType,
            RuntimeType constraint) {
        ResourceReader reader = new ResourceReader();
        reader.setFactory(recorder.factory(readerClass.getName(), beanContainer));
        reader.setMediaTypeStrings(Collections.singletonList(mediaType));
        reader.setConstraint(constraint);
        recorder.registerReader(serialisers, entityClass.getName(), reader);
    }

    private List<String> getProducesMediaTypes(ClassInfo classInfo) {
        AnnotationInstance produces = classInfo.classAnnotation(QuarkusRestDotNames.PRODUCES);
        if (produces == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(produces.value().asStringArray());
    }

    @BuildStep
    void beanDefiningAnnotations(BuildProducer<BeanDefiningAnnotationBuildItem> beanDefiningAnnotations) {
        beanDefiningAnnotations
                .produce(new BeanDefiningAnnotationBuildItem(QuarkusRestDotNames.PATH, BuiltinScope.SINGLETON.getName()));
        beanDefiningAnnotations
                .produce(new BeanDefiningAnnotationBuildItem(QuarkusRestDotNames.APPLICATION_PATH,
                        BuiltinScope.SINGLETON.getName()));
        beanDefiningAnnotations
                .produce(new BeanDefiningAnnotationBuildItem(QuarkusRestDotNames.PROVIDER,
                        BuiltinScope.SINGLETON.getName()));
    }

    private Map<String, RuntimeValue<Function<WebTarget, ?>>> generateClientInvokers(RecorderContext recorderContext,
            List<RestClientInterface> clientDefinitions,
            BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer) {
        Map<String, RuntimeValue<Function<WebTarget, ?>>> ret = new HashMap<>();
        for (RestClientInterface restClientInterface : clientDefinitions) {
            boolean subResource = false;
            //if the interface contains sub resource locator methods we ignore it
            for (ResourceMethod i : restClientInterface.getMethods()) {
                if (i.getHttpMethod() == null) {
                    subResource = true;
                }
                break;
            }
            if (subResource) {
                continue;
            }
            String name = restClientInterface.getClassName() + "$$QuarkusRestClientInterface";
            MethodDescriptor ctorDesc = MethodDescriptor.ofConstructor(name, WebTarget.class.getName());
            try (ClassCreator c = new ClassCreator(new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true),
                    name, null, Object.class.getName(), restClientInterface.getClassName())) {

                FieldDescriptor target = FieldDescriptor.of(name, "target", WebTarget.class);
                c.getFieldCreator(target).setModifiers(Modifier.FINAL);

                MethodCreator ctor = c.getMethodCreator(ctorDesc);
                ctor.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), ctor.getThis());

                ResultHandle res = ctor.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(WebTarget.class, "path", WebTarget.class, String.class),
                        ctor.getMethodParam(0), ctor.load(restClientInterface.getPath()));
                ctor.writeInstanceField(target, ctor.getThis(), res);
                ctor.returnValue(null);

                for (ResourceMethod method : restClientInterface.getMethods()) {
                    MethodCreator m = c.getMethodCreator(method.getName(), method.getReturnType(),
                            Arrays.stream(method.getParameters()).map(s -> s.type).toArray());
                    ResultHandle tg = m.readInstanceField(target, m.getThis());
                    if (method.getPath() != null) {
                        tg = m.invokeInterfaceMethod(MethodDescriptor.ofMethod(WebTarget.class, "path", WebTarget.class,
                                String.class), tg, m.load(method.getPath()));
                    }

                    for (int i = 0; i < method.getParameters().length; ++i) {
                        MethodParameter p = method.getParameters()[i];
                        if (p.parameterType == ParameterType.QUERY) {
                            //TODO: converters
                            ResultHandle array = m.newArray(Object.class, 1);
                            m.writeArrayValue(array, 0, m.getMethodParam(i));
                            tg = m.invokeInterfaceMethod(
                                    MethodDescriptor.ofMethod(WebTarget.class, "queryParam", WebTarget.class,
                                            String.class, Object[].class),
                                    tg, m.load(p.name), array);
                        }

                    }

                    ResultHandle builder;
                    if (method.getProduces() == null || method.getProduces().length == 0) {
                        builder = m.invokeInterfaceMethod(
                                MethodDescriptor.ofMethod(WebTarget.class, "request", Invocation.Builder.class), tg);
                    } else {

                        ResultHandle array = m.newArray(String.class, method.getProduces().length);
                        for (int i = 0; i < method.getProduces().length; ++i) {
                            m.writeArrayValue(array, i, m.load(method.getProduces()[i]));
                        }
                        builder = m.invokeInterfaceMethod(
                                MethodDescriptor.ofMethod(WebTarget.class, "request", Invocation.Builder.class, String[].class),
                                tg, array);
                    }
                    //TODO: async return types

                    ResultHandle result = m
                            .invokeInterfaceMethod(
                                    MethodDescriptor.ofMethod(Invocation.Builder.class, "method", Object.class, String.class,
                                            Class.class),
                                    builder, m.load(method.getHttpMethod()), m.loadClass(method.getSimpleReturnType()));

                    m.returnValue(result);
                }

            }
            String creatorName = restClientInterface.getClassName() + "$$QuarkusRestClientInterfaceCreator";
            try (ClassCreator c = new ClassCreator(new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true),
                    creatorName, null, Object.class.getName(), Function.class.getName())) {

                MethodCreator apply = c
                        .getMethodCreator(MethodDescriptor.ofMethod(creatorName, "apply", Object.class, Object.class));
                apply.returnValue(apply.newInstance(ctorDesc, apply.getMethodParam(0)));
            }
            ret.put(restClientInterface.getClassName(), recorderContext.newInstance(creatorName));

        }
        return ret;
    }

    private MethodInfo hasJaxRsCtorParams(ClassInfo classInfo) {
        List<MethodInfo> methods = classInfo.methods();
        List<MethodInfo> ctors = new ArrayList<>();
        for (MethodInfo method : methods) {
            if (method.name().equals("<init>")) {
                ctors.add(method);
            }
        }
        if (ctors.size() != 1) { // we only need to deal with a single ctor here
            return null;
        }
        MethodInfo ctor = ctors.get(0);
        if (ctor.parameters().size() == 0) { // default ctor - we don't need to do anything
            return null;
        }

        boolean needsHandling = false;
        for (DotName dotName : QuarkusRestDotNames.RESOURCE_CTOR_PARAMS_THAT_NEED_HANDLING) {
            if (ctor.hasAnnotation(dotName)) {
                needsHandling = true;
                break;
            }
        }
        return needsHandling ? ctor : null;
    }

}
