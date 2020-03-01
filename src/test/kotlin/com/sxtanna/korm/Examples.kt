package com.sxtanna.korm

import com.sxtanna.korm.data.custom.KormList
import com.sxtanna.korm.data.option.Options
import com.sxtanna.korm.writer.KormWriter
import org.junit.jupiter.api.Test

internal class Examples
{
	
	private val korm = Korm(writer = KormWriter(2, Options.none()))
	
	
	@KormList(["name"])
	data class Person(val name: Naming)
	
	@KormList(["name"])
	data class Naming(val name: String)
	
	@Test
	fun personExample()
	{
		val sxtannaObject = Person(Naming("Sxtanna"))
		println(sxtannaObject)
		
		val sxtannaString = korm.push(sxtannaObject)
		println(sxtannaString)
		
		println(korm.pull(sxtannaString).to<Person>())
	}
	
}