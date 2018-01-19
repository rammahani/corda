package net.corda.nodeapi.internal.serialization.amqp

import com.google.common.primitives.Primitives
import com.google.common.reflect.TypeToken
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationContext
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.lang.reflect.*
import java.lang.reflect.Field
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaType

/**
 * Annotation indicating a constructor to be used to reconstruct instances of a class during deserialization.
 */
@Target(AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConstructorForDeserialization

/**
 * Code for finding the constructor we will use for deserialization.
 *
 * If there's only one constructor, it selects that.  If there are two and one is the default, it selects the other.
 * Otherwise it starts with the primary constructor in kotlin, if there is one, and then will override this with any that is
 * annotated with [@CordaConstructor].  It will report an error if more than one constructor is annotated.
 */
internal fun constructorForDeserialization(type: Type): KFunction<Any>? {
    val clazz: Class<*> = type.asClass()!!
    if (isConcrete(clazz)) {
        var preferredCandidate: KFunction<Any>? = clazz.kotlin.primaryConstructor
        var annotatedCount = 0
        val kotlinConstructors = clazz.kotlin.constructors
        val hasDefault = kotlinConstructors.any { it.parameters.isEmpty() }
        for (kotlinConstructor in kotlinConstructors) {
            if (preferredCandidate == null && kotlinConstructors.size == 1) {
                preferredCandidate = kotlinConstructor
            } else if (preferredCandidate == null && kotlinConstructors.size == 2 && hasDefault && kotlinConstructor.parameters.isNotEmpty()) {
                preferredCandidate = kotlinConstructor
            } else if (kotlinConstructor.findAnnotation<ConstructorForDeserialization>() != null) {
                if (annotatedCount++ > 0) {
                    throw NotSerializableException("More than one constructor for $clazz is annotated with @CordaConstructor.")
                }
                preferredCandidate = kotlinConstructor
            }
        }

        return preferredCandidate?.apply { isAccessible = true }
                ?: throw NotSerializableException("No constructor for deserialization found for $clazz.")
    } else {
        return null
    }
}

/**
 * Identifies the properties to be used during serialization by attempting to find those that match the parameters to the
 * deserialization constructor, if the class is concrete.  If it is abstract, or an interface, then use all the properties.
 *
 * Note, you will need any Java classes to be compiled with the `-parameters` option to ensure constructor parameters have
 * names accessible via reflection.
 */
internal fun <T : Any> propertiesForSerialization(
        kotlinConstructor: KFunction<T>?,
        type: Type,
        factory: SerializerFactory) = PropertySerializers.make (
            if (kotlinConstructor != null) {
                propertiesForSerializationFromConstructor(kotlinConstructor, type, factory)
            } else {
                propertiesForSerializationFromAbstract(type.asClass()!!, type, factory)
            }.sortedWith(PropertyAccessor))


fun isConcrete(clazz: Class<*>): Boolean = !(clazz.isInterface || Modifier.isAbstract(clazz.modifiers))

/**
 * Encapsulates the property of a class and it's potential getter and setter methods.
 *
 * @property field a property of a class.
 * @property setter the method of a class that sets the field. Determined by locating
 * a function called setXyz on the class for the property named in field as xyz.
 * @property getter the method of a class that returns a fields value. Determined by
 * locating a function named getXyz for the property named in field as xyz.
 */
data class PropertyDescriptor (var field: Field?, var setter : Method?, var getter: Method?) {
    override fun toString() = StringBuilder("Property - ${field?.name ?: "null field"}\n").apply {
        appendln("  getter - ${getter?.name ?: "no getter"}")
        append("  setter - ${setter?.name ?: "no setter"}")
    }.toString()
}

/**
 * Collate the properties of a class and match them with their getter and setter
 * methods as per a JavaBean.
 */
fun Class<out Any?>.propertyDescriptors() : Map<String, PropertyDescriptor> {
    val classProperties = mutableMapOf<String, PropertyDescriptor>()

    var clazz : Class<out Any?>? = this
    do {
        // get the properties declared on this instance of class
        clazz?.declaredFields?.forEach { classProperties.put (it.name, PropertyDescriptor(it, null, null)) }

        // then pair them up with the declared getter and setter
        // Note: It is possible for a class to have multple instaces of a function where the types
        // differ. For example:
        //      interface I<out T> { val a: T }
        //      class D(override val a: String) : I<String>
        // instances of D will have both
        //      getA - returning a String (java.lang.String) and
        //      getA - returning an Object (java.lang.Object)
        // In this instance we take the most derived object
        clazz?.declaredMethods?.map {
            Regex("(?<type>get|set)(?<var>[A-Z].*)").find(it.name)?.apply {
                try {
                    classProperties.getValue(groups[2]!!.value.decapitalize()).apply {
                        when (groups[1]!!.value) {
                            "set" -> {
                                if (setter == null) setter = it
                                else if (TypeToken.of(setter!!.genericReturnType).isSupertypeOf(it.genericReturnType)) {
                                    setter = it
                                }
                            }
                            "get" -> {
                                if (getter == null) getter = it
                                else if (TypeToken.of(getter!!.genericReturnType).isSupertypeOf(it.genericReturnType)) {
                                    getter = it
                                }
                            }
                        }
                    }
                } catch (e: NoSuchElementException) {
                    // handles the getClass case from java.lang.Object
                    return@apply
                }
            }
        }
        clazz = clazz?.superclass
    } while (clazz != null)

    return classProperties
}

/**
 * From a constructor, determine which properties of a class are to be serialized.
 *
 * @param kotlinConstructor The constructor to be used to instantiate instances of the class
 * @param type The class's [Type]
 * @param factory The factory generating the serializer wrapping this function.
 */
internal fun <T : Any> propertiesForSerializationFromConstructor(
        kotlinConstructor: KFunction<T>,
        type: Type,
        factory: SerializerFactory): List<PropertyAccessor> {
    val clazz = (kotlinConstructor.returnType.classifier as KClass<*>).javaObjectType

    val classProperties = clazz.propertyDescriptors()

    if (classProperties.isNotEmpty() && kotlinConstructor.parameters.isEmpty()) {
        return propertiesForSerializationFromSetters(classProperties, type, factory)
    }

    return mutableListOf<PropertyAccessor>().apply {
        kotlinConstructor.parameters.withIndex().forEach { param ->
            val name = param.value.name ?: throw NotSerializableException(
                    "Constructor parameter of $clazz has no name.")

            val propertyReader = if (name in classProperties) {
                if (classProperties[name]!!.getter != null) {
                    // it's a publicly accessible property
                    val matchingProperty = classProperties[name]!!

                    // Check that the method has a getter in java.
                    val getter = matchingProperty.getter ?: throw NotSerializableException(
                            "Property has no getter method for $name of $clazz. If using Java and the parameter name"
                                    + "looks anonymous, check that you have the -parameters option specified in the "
                                    + "Java compiler. Alternately, provide a proxy serializer "
                                    + "(SerializationCustomSerializer) if recompiling isn't an option.")

                    val returnType = resolveTypeVariables(getter.genericReturnType, type)
                    if (!constructorParamTakesReturnTypeOfGetter(returnType, getter.genericReturnType, param.value)) {
                        throw NotSerializableException(
                                "Property '$name' has type '$returnType' on class '$clazz' but differs from constructor " +
                                "parameter type ${param.value.type.javaType}")
                    }

                    Pair(PublicPropertyReader(getter), returnType)
                } else {
                    try {
                        val field = clazz.getDeclaredField(param.value.name)
                        Pair(PrivatePropertyReader(field, type), field.genericType)
                    } catch (e: NoSuchFieldException) {
                        throw NotSerializableException("No property matching constructor parameter named '$name' " +
                                "of '$clazz'. If using Java, check that you have the -parameters option specified " +
                                "in the Java compiler. Alternately, provide a proxy serializer " +
                                "(SerializationCustomSerializer) if recompiling isn't an option")
                    }
                }
            } else {
                throw NotSerializableException(
                        "Constructor parameter $name doesn't refer to a property of class '$clazz'")
            }

            this += PropertyAccessorConstructor(
                    param.index,
                    PropertySerializer.make(name, propertyReader.first, propertyReader.second, factory))
        }
    }
}

/**
 * If we determine a class has a constructor that takes no parameters then check for pairs of getters / setters
 * and use those
 */
private fun propertiesForSerializationFromSetters(
        properties: Map<String, PropertyDescriptor>,
        type: Type,
        factory: SerializerFactory): List<PropertyAccessor> {
    return mutableListOf<PropertyAccessorGetterSetter>().apply {
        var idx = 0
        properties.forEach { property ->
            val getter: Method? = property.value.getter
            val setter: Method? = property.value.setter

            if (getter == null || setter == null) return@forEach

            if (setter.parameterCount != 1) {
                throw NotSerializableException("Defined setter for parameter ${property.value.field?.name} " +
                        "takes too many arguments")
            }

            val setterType = setter.parameterTypes.getOrNull(0)!!
            if (!(TypeToken.of(property.value.field?.genericType!!).isSupertypeOf(setterType))) {
                throw NotSerializableException("Defined setter for parameter ${property.value.field?.name} " +
                        "takes parameter of type $setterType yet underlying type is " +
                        "${property.value.field?.genericType!!}")
            }

            this += PropertyAccessorGetterSetter (
                    idx++,
                    PropertySerializer.make(property.value.field!!.name , PublicPropertyReader(getter),
                            resolveTypeVariables(getter.genericReturnType, type), factory),
                    setter)
        }
    }
}

private fun constructorParamTakesReturnTypeOfGetter(
        getterReturnType: Type,
        rawGetterReturnType: Type,
        param: KParameter): Boolean {

    val typeToken = TypeToken.of(param.type.javaType)
    return typeToken.isSupertypeOf(getterReturnType) || typeToken.isSupertypeOf(rawGetterReturnType)
}

private fun propertiesForSerializationFromAbstract(
        clazz: Class<*>,
        type: Type,
        factory: SerializerFactory): List<PropertyAccessor> {
    val properties = clazz.propertyDescriptors()

   return mutableListOf<PropertyAccessorConstructor>().apply {
       properties.toList().withIndex().forEach {
           val getter = it.value.second.getter ?: return@forEach
           val returnType = resolveTypeVariables(getter.genericReturnType, type)
           this += PropertyAccessorConstructor(
                   it.index,
                   PropertySerializer.make(it.value.first, PublicPropertyReader(getter), returnType, factory))
       }
   }
}

internal fun interfacesForSerialization(type: Type, serializerFactory: SerializerFactory): List<Type> {
    val interfaces = LinkedHashSet<Type>()
    exploreType(type, interfaces, serializerFactory)
    return interfaces.toList()
}

private fun exploreType(type: Type?, interfaces: MutableSet<Type>, serializerFactory: SerializerFactory) {
    val clazz = type?.asClass()
    if (clazz != null) {
        if (clazz.isInterface) {
            if (serializerFactory.whitelist.isNotWhitelisted(clazz)) return // We stop exploring once we reach a branch that has no `CordaSerializable` annotation or whitelisting.
            else interfaces += type
        }
        for (newInterface in clazz.genericInterfaces) {
            if (newInterface !in interfaces) {
                exploreType(resolveTypeVariables(newInterface, type), interfaces, serializerFactory)
            }
        }
        val superClass = clazz.genericSuperclass ?: return
        exploreType(resolveTypeVariables(superClass, type), interfaces, serializerFactory)
    }
}

/**
 * Extension helper for writing described objects.
 */
fun Data.withDescribed(descriptor: Descriptor, block: Data.() -> Unit) {
    // Write described
    putDescribed()
    enter()
    // Write descriptor
    putObject(descriptor.code ?: descriptor.name)
    block()
    exit() // exit described
}

/**
 * Extension helper for writing lists.
 */
fun Data.withList(block: Data.() -> Unit) {
    // Write list
    putList()
    enter()
    block()
    exit() // exit list
}

/**
 * Extension helper for outputting reference to already observed object
 */
fun Data.writeReferencedObject(refObject: ReferencedObject) {
    // Write described
    putDescribed()
    enter()
    // Write descriptor
    putObject(refObject.descriptor)
    putUnsignedInteger(refObject.described)
    exit() // exit described
}

private fun resolveTypeVariables(actualType: Type, contextType: Type?): Type {
    val resolvedType = if (contextType != null) TypeToken.of(contextType).resolveType(actualType).type else actualType
    // TODO: surely we check it is concrete at this point with no TypeVariables
    return if (resolvedType is TypeVariable<*>) {
        val bounds = resolvedType.bounds
        return if (bounds.isEmpty()) {
            SerializerFactory.AnyType
        } else if (bounds.size == 1) {
            resolveTypeVariables(bounds[0], contextType)
        } else throw NotSerializableException("Got bounded type $actualType but only support single bound.")
    } else {
        resolvedType
    }
}

internal fun Type.asClass(): Class<*>? {
    return when {
        this is Class<*> -> this
        this is ParameterizedType -> this.rawType.asClass()
        this is GenericArrayType -> this.genericComponentType.asClass()?.arrayClass()
        this is TypeVariable<*> -> this.bounds.first().asClass()
        this is WildcardType -> this.upperBounds.first().asClass()
        else -> null
    }
}

internal fun Type.asArray(): Type? {
    return when {
        this is Class<*> -> this.arrayClass()
        this is ParameterizedType -> DeserializedGenericArrayType(this)
        else -> null
    }
}

internal fun Class<*>.arrayClass(): Class<*> = java.lang.reflect.Array.newInstance(this, 0).javaClass

internal fun Type.isArray(): Boolean = (this is Class<*> && this.isArray) || (this is GenericArrayType)

internal fun Type.componentType(): Type {
    check(this.isArray()) { "$this is not an array type." }
    return (this as? Class<*>)?.componentType ?: (this as GenericArrayType).genericComponentType
}

internal fun Class<*>.asParameterizedType(): ParameterizedType {
    return DeserializedParameterizedType(this, this.typeParameters)
}

internal fun Type.asParameterizedType(): ParameterizedType {
    return when (this) {
        is Class<*> -> this.asParameterizedType()
        is ParameterizedType -> this
        else -> throw NotSerializableException("Don't know how to convert to ParameterizedType")
    }
}

internal fun Type.isSubClassOf(type: Type): Boolean {
    return TypeToken.of(this).isSubtypeOf(type)
}

// ByteArrays, primtives and boxed primitives are not stored in the object history
internal fun suitableForObjectReference(type: Type): Boolean {
    val clazz = type.asClass()
    return type != ByteArray::class.java && (clazz != null && !clazz.isPrimitive && !Primitives.unwrap(clazz).isPrimitive)
}

/**
 * Common properties that are to be used in the [SerializationContext.properties] to alter serialization behavior/content
 */
internal enum class CommonPropertyNames {
    IncludeInternalInfo,
}

/**
 * Utility function which helps tracking the path in the object graph when exceptions are thrown.
 * Since there might be a chain of nested calls it is useful to record which part of the graph caused an issue.
 * Path information is added to the message of the exception being thrown.
 */
internal inline fun <T> ifThrowsAppend(strToAppendFn: () -> String, block: () -> T): T {
    try {
        return block()
    } catch (th: Throwable) {
        th.setMessage("${strToAppendFn()} -> ${th.message}")
        throw th
    }
}

/**
 * Not a public property so will have to use reflection
 */
private fun Throwable.setMessage(newMsg: String) {
    val detailMessageField = Throwable::class.java.getDeclaredField("detailMessage")
    detailMessageField.isAccessible = true
    detailMessageField.set(this, newMsg)
}

fun ClassWhitelist.requireWhitelisted(type: Type) {
    if (!this.isWhitelisted(type.asClass()!!)) {
        throw NotSerializableException("Class $type is not on the whitelist or annotated with @CordaSerializable.")
    }
}

fun ClassWhitelist.isWhitelisted(clazz: Class<*>) = (hasListed(clazz) || hasAnnotationInHierarchy(clazz))
fun ClassWhitelist.isNotWhitelisted(clazz: Class<*>) = !(this.isWhitelisted(clazz))

// Recursively check the class, interfaces and superclasses for our annotation.
fun ClassWhitelist.hasAnnotationInHierarchy(type: Class<*>): Boolean {
    return type.isAnnotationPresent(CordaSerializable::class.java)
            || type.interfaces.any { hasAnnotationInHierarchy(it) }
            || (type.superclass != null && hasAnnotationInHierarchy(type.superclass))
}
