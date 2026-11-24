package ru.zinin.frigate.analyzer.core.config.transaction

import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.transaction.ReactiveTransactionManager

@Configuration
class TransactionManagerConfiguration {
    /** Вместо `R2dbcTransactionManager`, который Spring Boot объявил бы сам. */
    @Bean
    fun transactionManager(connectionFactory: ConnectionFactory): ReactiveTransactionManager =
        CancellationSafeTransactionManager(R2dbcTransactionManager(connectionFactory))
}
