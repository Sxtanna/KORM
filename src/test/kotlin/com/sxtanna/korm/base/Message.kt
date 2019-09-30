package com.sxtanna.korm.base

import com.sxtanna.korm.data.KormType
import com.sxtanna.korm.data.custom.KormCustomPull
import com.sxtanna.korm.reader.KormReader
import java.util.UUID
import kotlin.reflect.jvm.jvmName

@KormCustomPull(Message.Puller::class)
sealed class Message
{
	
	val name = this::class.simpleName ?: this::class.jvmName.substringAfterLast('.')
	
	
	class Delete : Message()
	
	
	data class Join(val playerUUID: UUID) : Message()
	
	data class Quit(val playerUUID: UUID) : Message()
	
	
	data class Error(val message: String) : Message()
	
	
	object Puller : KormPuller<Message>
	{
		
		override fun pull(reader: KormReader.ReaderContext, types: MutableList<KormType>): Message?
		{
			val name = types.find { it.key.data == "name" } ?: return null
			
			return when (name.asBase()?.data ?: return null)
			{
				"Join"  ->
				{
					reader.mapInstance(Join::class, types, this)
				}
				"Quit"  ->
				{
					reader.mapInstance(Quit::class, types, this)
				}
				"Error" ->
				{
					reader.mapInstance(Error::class, types, this)
				}
				else    ->
				{
					null
				}
			}
		}
		
	}
	
	
	interface Thing0
	
	interface Thing1
	
	interface Thing2 : Thing0, Thing1
	
}