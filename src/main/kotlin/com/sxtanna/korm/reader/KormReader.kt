package com.sxtanna.korm.reader

import com.sxtanna.korm.Korm
import com.sxtanna.korm.base.Exec
import com.sxtanna.korm.base.KormPuller
import com.sxtanna.korm.comp.Type.COMPLEX
import com.sxtanna.korm.comp.lexer.Lexer
import com.sxtanna.korm.comp.typer.Typer
import com.sxtanna.korm.data.Data
import com.sxtanna.korm.data.KormType
import com.sxtanna.korm.data.KormType.BaseType
import com.sxtanna.korm.data.KormType.HashType
import com.sxtanna.korm.data.KormType.ListType
import com.sxtanna.korm.data.RefType
import com.sxtanna.korm.data.custom.KormCustomCodec
import com.sxtanna.korm.data.custom.KormCustomPull
import com.sxtanna.korm.data.custom.KormList
import com.sxtanna.korm.util.Reflect
import java.io.*
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
import kotlin.reflect.full.cast
import kotlin.reflect.full.createInstance

/**
 * This thing literally reads from various sources and spits out korm types
 */
@Suppress("UNCHECKED_CAST", "NAME_SHADOWING", "MemberVisibilityCanBePrivate")
class KormReader
{
	
	@Transient
	internal lateinit var korm: Korm
	
	/**
	 * Creates a [ReaderContext] for the provided [reader]
	 */
	fun context(reader: Reader): ReaderContext
	{
		return ReaderContext(reader)
	}
	
	/**
	 * Creates a [FileReader] from the given [file] and executes a [ReaderContext]
	 *
	 * - Fails silently for files that don't exist, or are directories
	 */
	fun read(file: File): ReaderContext
	{
		return if (file.exists().not() || file.isDirectory)
		{
			read("")
		}
		else
		{
			read(FileReader(file))
		}
	}
	
	/**
	 * Creates a [StringReader] from the given [text] and executes a [ReaderContext]
	 */
	fun read(text: String): ReaderContext
	{
		return read(StringReader(text))
	}
	
	/**
	 * Creates a [InputStreamReader] from the given [stream] using the given [charset] and executes a [ReaderContext]
	 */
	fun read(stream: InputStream, charset: Charset = Charset.defaultCharset()): ReaderContext
	{
		return read(InputStreamReader(stream, charset))
	}
	
	/**
	 * Creates a [ReaderContext] from the given [reader] and executes it
	 */
	fun read(reader: Reader): ReaderContext
	{
		return context(reader).apply { exec() }
	}
	
	
	/**
	 * Takes information provided by a reader and transforms it into a list of [KormType]
	 *
	 * **Routine Process**
	 * - Lex the input with [Lexer]
	 * - Type the input with [Typer]
	 * - Use list of [KormType] to create usable objects
	 *
	 */
	inner class ReaderContext internal constructor(private val reader: Reader) : Exec<Unit>
	{
		
		private val types = mutableListOf<KormType>()
		private val cache = mutableMapOf<Type, Any>() // testing out inner class deserialization
		
		
		override fun exec()
		{
			val input = reader.buffered().use { it.readText() }
			if (input.isBlank()) return
			
			val lexer = Lexer(input)
			val typer = Typer(lexer.exec())
			
			types += typer.exec()
		}
		
		
		fun viewTypes(): List<KormType>
		{
			return types
		}
		
		
		// directly to a class
		fun <T : Any> to(clazz: KClass<T>): T?
		{
			if (types.isEmpty())
			{
				return null
			}
			
			return internalTo(clazz, clazz.java)
		}
		
		inline fun <reified T : Any> to(): T?
		{
			return to(T::class)
		}
		
		@Deprecated("this is gonna be removed", ReplaceWith("to(clazz as Type)", "java.lang.reflect.Type"), DeprecationLevel.ERROR)
		fun <T : Any> to(clazz: Class<T>): T?
		{
			return to(clazz as Type) // who doesn't love enhanced Java Interop?
		}
		
		fun <T : Any> to(type: Type): T?
		{
			if (types.isEmpty())
			{
				return null
			}
			
			val clazz = if (type !is ParameterizedType)
			{
				type
			}
			else
			{
				type.rawType
			}
			
			return internalTo(Reflect.nonPrimitive((clazz as Class<*>).kotlin), type)
		}
		
		
		private fun <T : Any> internalTo(clazz: KClass<*>, type: Type): T?
		{
			try
			{
				if (!Reflect.isKormType(clazz))
				{
					return mapInstance(clazz) as? T
				}
				
				val korm = if (types.size == 1)
				{
					types.single()
				}
				else
				{
					HashType(Data.none(), types)
				}
				
				return Reflect.ensureIs(mapKormToType(korm, type) ?: return null, clazz) as? T
			}
			catch (ex: Exception)
			{
				return null
			}
		}
		
		
		fun <T : Any> toRef(type: RefType<T>): T?
		{
			return to(type.type())
		}
		
		inline fun <reified T : Any> toRef(): T?
		{
			return toRef(RefType.of<T>())
		}
		
		
		// to a list
		fun <T : Any> toList(clazz: KClass<T>): List<T>
		{
			val type = checkNotNull(types.singleOrNull() as? ListType) {
				"This does not represent a list"
			}
			
			return mapListData(type, List::class, clazz.java) as List<T>
		}
		
		inline fun <reified T : Any> toList(): List<T>
		{
			return toList(T::class)
		}
		
		
		fun <T : Any> toListRef(ref: RefType<T>): List<T>
		{
			val type = checkNotNull(types.singleOrNull() as? ListType) {
				"This does not represent a list"
			}
			
			return mapListData(type, List::class, ref.type()) as List<T>
		}
		
		inline fun <reified T : Any> toListRef(): List<T>
		{
			return toListRef(RefType.of())
		}
		
		
		// to a hash
		fun <K : Any, V : Any> toHash(kType: KClass<K>, vType: KClass<V>): Map<K, V>
		{
			val type = checkNotNull(types.singleOrNull() as? HashType) {
				"This does not represent a hash"
			}
			
			return mapHashData(type, Map::class, kType.java, vType.java) as Map<K, V>
		}
		
		inline fun <reified K : Any, reified V : Any> toHash(): Map<K, V>
		{
			return toHash(K::class, V::class)
		}
		
		
		fun <K : Any, V : Any> toHashRef(kRef: RefType<K>, vRef: RefType<V>): Map<K, V>
		{
			val type = checkNotNull(types.singleOrNull() as? HashType) {
				"This does not represent a hash"
			}
			
			return mapHashData(type, Map::class, kRef.type(), vRef.type()) as Map<K, V>
		}
		
		inline fun <reified K : Any, reified V : Any> toHashRef(): Map<K, V>
		{
			return toHashRef(RefType.of(), RefType.of())
		}
		
		
		fun <T : Any> mapInstance(clazz: KClass<out T>, types: MutableList<KormType> = this.types, caller: KormPuller<*>? = null): T?
		{
			if (types.isEmpty())
			{
				return null
			}
			
			val custom = getCustomPull(clazz, caller)
			
			return if (custom != null)
			{
				custom.pull(this, types)
			}
			else
			{
				val instance = Reflect.newInstance(clazz) ?: return null
				cache[clazz.java] = instance
				
				val asList = Reflect.findAnnotation<KormList>(clazz)?.props?.toList()
				
				if (asList == null)
				{
					val fields = Reflect.access(clazz)
					
					for (field in fields)
					{
						if (field.isInnerRef)
						{
							Reflect.assign(field, instance, cache[field.genericType] ?: continue)
							continue
						}
						
						val list = if (types.size == 1 && types[0].asHash()?.key?.asString() == "")
						{
							types[0].asHash()?.data
						}
						else
						{
							types
						}
						
						val korm = list?.find { it.key.data.toString() == field.name } ?: continue
						
						if (list is MutableList)
						{
							list.remove(korm)
						}
						
						val data = mapKormToType(korm, field.genericType) ?: continue
						Reflect.assign(field, instance, data)
					}
				}
				else
				{
					val data = (types.single() as ListType).data
					
					val fields = Reflect.access(clazz).sortedBy { asList.indexOf(it.name) }
					
					for ((index, field) in fields.withIndex())
					{
						field[instance] = mapDataToType(data[index], field.genericType) ?: continue
					}
				}
				
				instance
			}
		}
		
		
		// korm mappers
		fun mapKormToType(korm: KormType, type: Type): Any?
		{
			return when (korm)
			{
				is BaseType ->
				{
					if (type is WildcardType)
					{
						return mapKormToType(korm, type.upperBounds[0])
					}
					
					val custom = getCustomPull((type as Class<*>).kotlin)
					if (custom != null)
					{
						return custom.pull(this, mutableListOf(korm))
					}
					
					mapDataToType(korm.data, type)
				}
				is ListType          ->
				{
					when (type)
					{
						is Class<*>         ->
						{
							val custom = getCustomPull(type.kotlin)
							if (custom != null)
							{
								return custom.pull(this, mutableListOf(korm))
							}
							
							if (type.isArray.not())
							{
								if (Reflect.findAnnotation<KormList>(type.kotlin) != null)
								{
									return mapInstance<Any>(type.kotlin, mutableListOf(korm))
								}
								return null
							}
							
							mapList(korm.data, List::class, type.componentType)?.let { list ->
								val list = list as List<Any>
								Array(list.size) { list[it] }
							}
						}
						is GenericArrayType ->
						{
							mapListData(korm, List::class, type.genericComponentType)
						}
						is WildcardType     ->
						{
							mapKormToType(korm, type.upperBounds[0])
						}
						else                ->
						{
							val type = type as ParameterizedType
							
							val custom = getCustomPull((type.rawType as Class<*>).kotlin)
							if (custom != null)
							{
								return custom.pull(this, mutableListOf(korm))
							}
							
							mapListData(korm, (type.rawType as Class<*>).kotlin, type.actualTypeArguments[0])
						}
					}
				}
				is HashType ->
				{
					when (type)
					{
						is Class<*>     ->
						{
							mapInstance(type.kotlin, korm.data.toMutableList())
						}
						is WildcardType ->
						{
							mapKormToType(korm, type.upperBounds[0])
						}
						else            ->
						{
							val type = type as ParameterizedType
							
							val (kType, vType) = when (type.rawType as Class<*>)
							{
								Pair::class.java      ->
								{
									String::class.java to type.actualTypeArguments[1]
								}
								Map.Entry::class.java ->
								{
									String::class.java to type.actualTypeArguments[1]
								}
								else                  ->
								{
									type.actualTypeArguments[0] to type.actualTypeArguments[1]
								}
							}
							
							val data = mapHashData(korm, (type.rawType as Class<*>).kotlin, kType, vType)
							
							// assign manually
							when (type.rawType as Class<*>)
							{
								Pair::class.java      ->
								{
									Pair(data?.get("first"), data?.get("second"))
								}
								Map.Entry::class.java ->
								{
									object : Map.Entry<Any?, Any?>
									{
										override val key = data?.get("key")
										override val value = data?.get("value")
									}
								}
								else                  ->
								{
									data
								}
							}
						}
					}
				}
			}
		}
		
		// why is this not being used??
		fun mapBaseData(korm: BaseType, clazz: KClass<*>): Any?
		{
			val custom = getCustomPull(clazz)
			if (custom != null)
			{
				return custom.pull(this, mutableListOf(korm))
			}
			
			return mapData(korm.data, clazz)
		}
		
		fun mapListData(korm: ListType, clazz: KClass<*>, type: Type): Collection<Any>?
		{
			val data = korm.data.map {
				mapDataToType(it, type, (it as? KormType)?.key?.type == COMPLEX)
			}
			
			return mapList(data, clazz, type)
		}
		
		fun mapHashData(korm: HashType, clazz: KClass<*>, kType: Type, vType: Type): Map<Any, Any>?
		{
			val data = korm.data.associate {
				mapDataToType(it.key.data, kType, it.key.type == COMPLEX) to mapKormToType(it, vType)
			}
			
			return mapHash(data, clazz, kType, vType)
		}
		
		
		fun mapDataToType(data: Any?, type: Type, complex: Boolean = false): Any?
		{
			val data = data ?: return null
			
			if (data is KormType)
			{
				return mapKormToType(data, type)
			}
			
			if (data is String && complex)
			{ // ffs here we go... we gotta deserialize complex keys
				return read(data).to(type)
			}
			
			when (type)
			{
				is Class<*>          ->
				{
					if (type.isInstance(data))
					{
						return data
					}
					
					return if (type.isArray)
					{ // handle array
						mapList(data, List::class, type.componentType)?.let { list ->
							val list = list as List<Any>
							Array(list.size) { list[it] }
						}
					}
					else
					{
						mapData(data, type.kotlin)
					}
				}
				is GenericArrayType  ->
				{
					val arg = type.genericComponentType
					
					when (data)
					{
						is Collection<*> ->
						{
							val list = data.toList()
							return Array(data.size) { mapDataToType(list[it], arg) }
						}
						is Map<*, *>     ->
						{
							
							if (data.isNotEmpty())
							{
								checkNotNull(data.keys.first() is Int) { "Cannot map a map to an array unless it's keys are Int" }
							}
							
							val data = (data as? Map<Int, Any>) ?: return null
							val size = (data.keys.max() ?: 0) + 1
							
							return Array(size) { mapDataToType(data[it], arg) }
						}
					}
				}
				is WildcardType      ->
				{
					return mapDataToType(data, type.upperBounds[0], complex)
				}
				is ParameterizedType ->
				{
					val typeArgs = type.actualTypeArguments
					val typeType = (type.rawType as Class<*>).kotlin
					
					if (typeType.isInstance(data))
					{
						return data
					}
					
					when
					{
						Reflect.isSubType(typeType, Collection::class) ->
						{
							check(Reflect.isListType(data::class)) {
								"Cannot map $data to list"
							}
							
							return mapList(data, typeType, typeArgs[0])
						}
						Reflect.isSubType(typeType, Map::class)        ->
						{
							check(Reflect.isHashType(data::class)) {
								"Cannot map $data to hash"
							}
							
							return mapHash(data, typeType, typeArgs[0], typeArgs[1])
						}
					}
				}
			}
			
			return null
		}
		
		
		// data mappers
		fun <T : Any> mapData(data: Any?, clazz: KClass<T>): T?
		{
			data ?: return null
			
			val clazz = Reflect.nonPrimitive(clazz)
			
			if (data is KormType)
			{
				return mapKormToType(data, clazz.java) as? T
			}
			
			when
			{
				clazz == UUID::class                          ->
				{
					return clazz.cast(UUID.fromString(data as String))
				}
				clazz == String::class                        ->
				{
					return clazz.cast(data as? String ?: data.toString())
				}
				clazz == Reflect.nonPrimitive(Char::class)    ->
				{
					return clazz.cast(data as? Char ?: (data as? String)?.first() ?: data.toString().first())
				}
				clazz == Reflect.nonPrimitive(Boolean::class) ->
				{
					return clazz.cast(data as? Boolean)
				}
				Reflect.isSubType(clazz, Number::class)       ->
				{
					var number = data as? Number ?: return null
					
					// I hate this, change it...
					number = when (clazz)
					{
						Byte::class          -> number.toByte()
						Short::class         -> number.toShort()
						Int::class           -> number.toInt()
						Long::class          -> number.toLong()
						Float::class         -> number.toFloat()
						Double::class        -> number.toDouble()
						AtomicInteger::class -> AtomicInteger(number.toInt())
						AtomicLong::class    -> AtomicLong(number.toLong())
						else                 -> number
					}
					
					return clazz.cast(number)
				}
				Reflect.isSubType(clazz, Enum::class)         ->
				{
					return clazz.java.enumConstants.find { (it as Enum<*>).name.equals(data.toString(), true) }
				}
			}
			
			return null
			
		}
		
		inline fun <reified T : Any> mapData(data: Any?): T?
		{
			return mapData(data, T::class)
		}
		
		fun mapList(data: Any?, clazz: KClass<*>, type: Type): Collection<Any>?
		{
			val data = data ?: return null
			val find = Reflect.findListType(clazz) ?: return null
			
			return Reflect.makeSequence(data).mapNotNullTo(find)
			{
				mapDataToType(it, type)
			}
		}
		
		fun mapHash(data: Any?, clazz: KClass<*>, kType: Type, vType: Type): Map<Any, Any>?
		{
			data ?: return null
			
			val hash = Reflect.findHashType(clazz) ?: return null
			
			(data as Map<*, *>).forEach { (k, v) ->
				val kData = k ?: return@forEach
				val vData = v ?: return@forEach
				
				val kOutP = mapDataToType(kData, kType) ?: return@forEach
				val vOutP = mapDataToType(vData, vType) ?: return@forEach
				
				hash[kOutP] = vOutP
			}
			
			return hash
		}
		
		
		fun <T : Any> getCustomPull(clazz: KClass<T>, caller: KormPuller<*>? = null): KormPuller<T>?
		{
			val stored = korm.pullerOf(clazz)
			if (stored != null)
			{
				return stored
			}
			
			val puller = Reflect.findAnnotation<KormCustomPull>(clazz)
			if (puller != null)
			{
				return extractFrom(puller.puller)
			}
			
			val codec = Reflect.findAnnotation<KormCustomCodec>(clazz)
			if (codec != null)
			{
				return extractFrom(codec.codec)
			}
			
			Reflect.nextSuperClasses(clazz).forEach {
				
				val puller = getCustomPull(it)
				
				if (caller != null && caller == puller)
				{
					return null // no stack overflows
				}
				
				if (puller != null)
				{
					return puller as? KormPuller<T>
				}
			}
			
			return null
		}
		
		
		private fun <T : Any> extractFrom(clazz: KClass<out KormPuller<*>>): KormPuller<T>?
		{
			return clazz.let { it.objectInstance ?: it.createInstance() } as? KormPuller<T>
		}
		
	}
	
}