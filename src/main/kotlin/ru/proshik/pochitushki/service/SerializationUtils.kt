package ru.proshik.pochitushki.service

import com.fasterxml.jackson.annotation.JsonInclude
import java.io.InputStream
import kotlin.reflect.KClass
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

object SerializationUtils {

    // Jackson 3: маппер неизменяемый, настраивается только билдером; java.time встроен в ядро.
    private val mapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
        .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
        .changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
        .addModule(KotlinModule.Builder().build())
        .build()


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