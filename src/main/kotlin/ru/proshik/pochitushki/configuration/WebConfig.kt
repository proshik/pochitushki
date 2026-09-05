package ru.proshik.pochitushki.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(private val jwtAuthInterceptor: JwtAuthInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(jwtAuthInterceptor)
            .addPathPatterns(
                "/", "/all", "/archive", "/favorites", "/random",
                "/labels", "/labels/**", "/profile", "/api/v1/**",
            )
    }
}
