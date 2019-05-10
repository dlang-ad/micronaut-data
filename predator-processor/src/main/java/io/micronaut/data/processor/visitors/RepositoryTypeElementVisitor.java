package io.micronaut.data.processor.visitors;

import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.service.ServiceDefinition;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.data.annotation.ParameterRole;
import io.micronaut.data.annotation.Persisted;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.intercept.PredatorInterceptor;
import io.micronaut.data.intercept.annotation.PredatorMethod;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.query.Query;
import io.micronaut.data.model.query.Sort;
import io.micronaut.data.model.query.builder.PreparedQuery;
import io.micronaut.data.model.query.builder.QueryBuilder;
import io.micronaut.data.processor.model.SourcePersistentEntity;
import io.micronaut.data.processor.model.SourcePersistentProperty;
import io.micronaut.data.processor.visitors.finders.*;
import io.micronaut.inject.ast.*;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The main {@link TypeElementVisitor} that visits interfaces annotated with {@link Repository}
 * and generates queries for each abstract method.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class RepositoryTypeElementVisitor implements TypeElementVisitor<Repository, Object> {

    private ClassElement currentClass;
    private QueryBuilder queryEncoder;
    private Map<String, String> parameterRoles = new HashMap<>();
    private List<MethodCandidate> finders;

    /**
     * Default constructor.
     */
    public RepositoryTypeElementVisitor() {

    }

    @Override
    public void start(VisitorContext visitorContext) {
        if (finders == null) {
            finders = initializeMethodCandidates(visitorContext);
        }
        parameterRoles.put(Pageable.class.getName(), ParameterRole.PAGEABLE);
        parameterRoles.put(Sort.class.getName(), ParameterRole.SORT);
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        this.currentClass = element;
        queryEncoder = resolveQueryEncoder(element, context);
        AnnotationValue[] roleArray = element.getAnnotationMetadata().getValue(Repository.class, "roleArray", AnnotationValue[].class).orElse(new AnnotationValue[0]);
        for (AnnotationValue<?> parameterRole : roleArray) {
            String role = parameterRole.get("role", String.class).orElse(null);
            AnnotationClassValue cv = parameterRole.get("type", AnnotationClassValue.class).orElse(null);
            if (StringUtils.isNotEmpty(role) && cv != null) {
                context.getClassElement(cv.getName()).ifPresent(ce ->
                    parameterRoles.put(ce.getName(), role)
                );
            }
        }
        if (queryEncoder == null) {
            context.fail("QueryEncoder not present on annotation processor path", element);
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        ClassElement genericReturnType = element.getGenericReturnType();
        if (genericReturnType != null && queryEncoder != null && currentClass != null && element.isAbstract() && !element.isStatic() && finders != null) {
            ParameterElement[] parameters = element.getParameters();
            Map<String, Element> parametersInRole = new HashMap<>(2);
            for (ParameterElement parameter : parameters) {
                ClassElement type = parameter.getType();
                if (type != null) {
                    this.parameterRoles.entrySet().stream().filter(entry ->
                            {
                                String roleType = entry.getKey();
                                return type.isAssignable(roleType);
                            }
                    ).forEach(entry ->
                        parametersInRole.put(entry.getValue(), parameter)
                    );
                }
            }

            for (MethodCandidate finder : finders) {
                if (finder.isMethodMatch(element)) {
                    SourcePersistentEntity entity = resolvePersistentEntity(element, parametersInRole, context);

                    if (entity == null) {
                        context.fail("Unable to establish persistent entity to query", element);
                        return;
                    }

                    String idType = resolveIdType(entity);


                    MethodMatchInfo methodInfo = finder.buildMatchInfo(new MethodMatchContext(
                            entity,
                            context,
                            genericReturnType,
                            element,
                            parametersInRole,
                            parameters
                    ));
                    if (methodInfo != null) {

                        // populate parameter roles
                        for (Map.Entry<String, Element> entry : parametersInRole.entrySet()) {
                            methodInfo.addParameterRole(
                                    entry.getKey(),
                                    entry.getValue().getName()
                            );
                        }

                        Query queryObject = methodInfo.getQuery();
                        Map<String, String> parameterBinding = null;
                        if (queryObject != null) {
                            if (queryObject instanceof RawQuery) {
                                RawQuery rawQuery = (RawQuery) queryObject;

                                // no need to annotation since already annotated, just replace the
                                // the computed parameter names
                                parameterBinding = rawQuery.getParameterBinding();
                            } else {
                                PreparedQuery encodedQuery;
                                try {
                                    switch (methodInfo.getOperationType()) {
                                        case DELETE:
                                            encodedQuery = queryEncoder.buildDelete(queryObject);
                                            break;
                                        case UPDATE:
                                            encodedQuery = queryEncoder
                                                    .buildUpdate(
                                                            queryObject,
                                                            methodInfo.getUpdateProperties());
                                            break;
                                        case INSERT:
                                            // TODO
                                        default:
                                            encodedQuery = queryEncoder.buildQuery(queryObject);
                                    }

                                } catch (Exception e) {
                                    context.fail("Invalid query method: " + e.getMessage(), element);
                                    return;
                                }

                                parameterBinding = encodedQuery.getParameters();
                                element.annotate(io.micronaut.data.annotation.Query.class, annotationBuilder ->
                                        annotationBuilder.value(encodedQuery.getQuery())
                                );
                            }
                        }

                        Class<? extends PredatorInterceptor> runtimeInterceptor = methodInfo.getRuntimeInterceptor();

                        if (runtimeInterceptor != null) {
                            Map<String, String> finalParameterBinding = parameterBinding;
                            element.annotate(PredatorMethod.class, annotationBuilder -> {
                                annotationBuilder.member("rootEntity", new AnnotationClassValue<>(entity.getName()));

                                // include the roles
                                methodInfo.getParameterRoles()
                                        .forEach(annotationBuilder::member);

                                TypedElement resultType = methodInfo.getResultType();
                                if (resultType != null) {
                                    annotationBuilder.member("resultType", new AnnotationClassValue<>(resultType.getName()));
                                }
                                if (idType != null) {
                                    annotationBuilder.member("idType", idType);
                                }
                                annotationBuilder.member("interceptor", runtimeInterceptor);
                                if (finalParameterBinding != null) {
                                    AnnotationValue<?>[] annotationParameters = new AnnotationValue[finalParameterBinding.size()];
                                    int i = 0;
                                    for (Map.Entry<String, String> entry : finalParameterBinding.entrySet()) {
                                        annotationParameters[i++] = AnnotationValue.builder(Property.class)
                                                .member("name", entry.getKey())
                                                .member("value", entry.getValue())
                                                .build();
                                    }
                                    annotationBuilder.member("parameterBinding", annotationParameters);
                                }
                                Optional<ParameterElement> entityParam = Arrays.stream(parameters).filter(p -> {
                                    ClassElement t = p.getGenericType();
                                    return t != null && t.isAssignable(entity.getName());
                                }).findFirst();
                                entityParam.ifPresent(parameterElement -> annotationBuilder.member("entity", parameterElement.getName()));

                                for (Map.Entry<String, String> entry : methodInfo.getParameterRoles().entrySet()) {
                                    annotationBuilder.member(entry.getKey(), entry.getValue());
                                }
                                if (queryObject != null) {
                                    int max = queryObject.getMax();
                                    if (max > -1) {
                                        annotationBuilder.member("max", max);
                                    }
                                    long offset = queryObject.getOffset();
                                    if (offset > 0) {
                                        annotationBuilder.member("offset", offset);
                                    }
                                }
                            });
                            return;
                        } else {
                            context.fail("Unable to implement Repository method: " + currentClass.getSimpleName() + "." + element.getName() + "(..). No possible runtime implementations found.", element);
                        }
                    }

                }
            }

            context.fail("Unable to implement Repository method: " + currentClass.getSimpleName() + "." + element.getName() + "(..). No possible implementations found.", element);
        }
    }

    private List<MethodCandidate> initializeMethodCandidates(VisitorContext context) {
        List<MethodCandidate> finderList = Arrays.asList(
                new FindByFinder(),
                new ExistsByFinder(),
                new SaveMethod(),
                new SaveAllMethod(),
                new ListMethod(),
                new CountMethod(),
                new DeleteByMethod(),
                new DeleteMethod(),
                new QueryListMethod(),
                new QueryOneMethod(),
                new CountByMethod(),
                new UpdateMethod(),
                new UpdateByMethod()
        );
        SoftServiceLoader<MethodCandidate> otherCandidates = SoftServiceLoader.load(MethodCandidate.class);
        for (ServiceDefinition<MethodCandidate> definition : otherCandidates) {
            if (definition.isPresent()) {
                try {
                    finderList.add(definition.load());
                } catch (Exception e) {
                    context.warn("Could not load Predator method candidate [" + definition.getName() + "]: " + e.getMessage(), null);
                }
            }
        }
        OrderUtil.sort(finderList);
        return finderList;
    }

    private @Nullable String resolveIdType(PersistentEntity entity) {
        Map<String, ClassElement> typeArguments = currentClass.getTypeArguments(io.micronaut.data.repository.Repository.class);
        if (!typeArguments.isEmpty()) {
            ClassElement ce = typeArguments.get("ID");
            if (ce != null) {
                return ce.getName();
            }
        }
        PersistentProperty identity = entity.getIdentity();
        if (identity != null) {
            return identity.getName();
        }
        return null;
    }

    private @Nullable SourcePersistentEntity resolvePersistentEntity(MethodElement element, Map<String, Element> parametersInRole, VisitorContext context) {
        ClassElement returnType = element.getGenericReturnType();
        SourcePersistentEntity entity = resolvePersistentEntity(returnType);
        if (entity == null) {
            Map<String, ClassElement> typeArguments = currentClass.getTypeArguments(io.micronaut.data.repository.Repository.class);
            if (!typeArguments.isEmpty()) {
                ClassElement ce = typeArguments.get("E");
                if (ce != null) {
                    entity = new SourcePersistentEntity(ce);
                }
            }
        }

        if (entity != null) {
            List<PersistentProperty> propertiesInRole = entity.getPersistentProperties()
                    .stream().filter(pp -> pp.getAnnotationMetadata().hasStereotype(ParameterRole.class))
                    .collect(Collectors.toList());
            for (PersistentProperty persistentProperty : propertiesInRole) {
                String role = persistentProperty.getAnnotationMetadata().getValue(ParameterRole.class, "role", String.class).orElse(null);
                if (role != null) {
                    parametersInRole.put(role, ((SourcePersistentProperty) persistentProperty).getPropertyElement());
                }
            }
            return entity;
        } else {
            context.fail("Could not resolved root entity. Either implement the Repository interface or define the entity as part of the signature", element);
            return null;
        }
    }

    private SourcePersistentEntity resolvePersistentEntity(ClassElement returnType) {
        if (returnType != null) {
            if (returnType.hasAnnotation(Persisted.class)) {
                return new SourcePersistentEntity(returnType);
            } else {
                Collection<ClassElement> typeArguments = returnType.getTypeArguments().values();
                for (ClassElement typeArgument : typeArguments) {
                    SourcePersistentEntity entity = resolvePersistentEntity(typeArgument);
                    if (entity != null) {
                        return entity;
                    }
                }
            }
        }
        return null;
    }

    private QueryBuilder resolveQueryEncoder(Element element, VisitorContext context) {
        return element.getValue(
                                Repository.class,
                                "queryBuilder",
                                String.class
                        ).flatMap(type -> {
                            Object o = InstantiationUtils.tryInstantiate(type, RepositoryTypeElementVisitor.class.getClassLoader()).orElse(null);
                            if (o instanceof QueryBuilder) {
                                return Optional.of((QueryBuilder) o);
                            } else {
                                context.fail("QueryEncoder of type [" + type + "] not present on annotation processor path", element);
                                return Optional.empty();
                            }
                        }).orElse(null);
    }
}
