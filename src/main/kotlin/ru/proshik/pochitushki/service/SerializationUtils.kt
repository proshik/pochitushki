package ru.proshik.pochitushki.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.InputStream
import kotlin.reflect.KClass

object SerializationUtils {

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())!!


    fun toJson(obj: Any): String = mapper.writeValueAsString(obj)

    fun toPrettyJson(obj: Any): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj)

    /**
     * Десериализует json в объект
     * @param json данные для десериализации
     * @param clazz class в который будет десериализован json
     * @return десериализованный объект
     */
    fun <T> fromJson(json: String, clazz: Class<T>): T = mapper.readValue(json, clazz)


    /**
     * Десериализует json в объект
     * @param json данные для десериализации
     * @param kClass kotlin-class в который будет десериализован json
     * @return десериализованный объект
     */
    fun <T : Any> fromJson(json: String, kClass: KClass<T>): T = mapper.readValue(json, kClass.java)

    /**
     * Десериализует json в объект
     * @param json данные для десериализации
     * @param typeReference референс на class в который будет десериализован json
     * @return десериализованный объект
     */
    fun <T> fromJson(json: String, typeReference: TypeReference<T>): T = mapper.readValue(json, typeReference)

    /**
     * Десериализует json в объект
     * @param json данные для десериализации
     * @param typeReference референс на class в который будет десериализован json
     * @return десериализованный объект
     */
    fun <T : Any> fromJson(json: InputStream, kClass: KClass<T>): T = mapper.readValue(json, kClass.java)

    /**
     * Десериализует json в объект
     * @param json данные для десериализации
     * @return десериализованный объект
     */
    inline fun <reified T : Any> fromJson(json: String): T = fromJson(json, T::class)

    /**
     * Десериализует json в объект
     * @param json данные для десериализации
     * @return десериализованный объект
     */
    inline fun <reified T : Any> fromJson(json: InputStream): T = fromJson(json, T::class)

}