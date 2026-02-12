package ru.zinin.frigate.analyzer.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.containers.startupcheck.IndefiniteWaitOneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [FrigateAnalyzerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(value = ["default", "test"])
abstract class IntegrationTestBase {
    companion object {
        val SERVICE_DB: String = "frigate-analyzer-db"
        val SERVICE_LIQUIBASE: String = "frigate-analyzer-liquibase"
        val PORT_DB: Int = 5432

        val ENVIRONMENT_TEST: ComposeContainer =
            ComposeContainer(DockerComposeFinder.findCompose().toFile())
                .withExposedService(SERVICE_DB, PORT_DB)
                .waitingFor(SERVICE_DB, Wait.forHealthcheck())
                .waitingFor(SERVICE_LIQUIBASE, IndefiniteWaitOneShotWaitStrategy())

        init {
            Startables.deepStart(Stream.of(ENVIRONMENT_TEST)).join()

            System.setProperty("DB_HOST", ENVIRONMENT_TEST.getServiceHost(SERVICE_DB, PORT_DB))
            System.setProperty("DB_PORT", ENVIRONMENT_TEST.getServicePort(SERVICE_DB, PORT_DB).toString())
        }
    }
}

object DockerComposeFinder {
    fun findCompose(): Path {
        val partial = Path.of("docker", "test-compose.yml")
        var currentDir = Path.of("").toAbsolutePath()

        logger.info { "Starting to search for $partial from current directory: $currentDir" }

        // Maximum number of iterations up the directory tree
        val maxIterationsUp = 3

        // Search up the directory tree with limitation
        for (iteration in 0..maxIterationsUp) {
            val potentialPath = currentDir.resolve(partial)
            logger.info { "Checking file existence at: $potentialPath" }

            if (potentialPath.exists()) {
                logger.info { "File found at: $potentialPath" }
                return potentialPath
            }

            // If we've reached the maximum number of iterations, exit
            if (iteration == maxIterationsUp) {
                logger.error { "Failed to find $partial - reached maximum search depth ($maxIterationsUp levels up)" }
                throw RuntimeException("Failed to find $partial within $maxIterationsUp parent directories")
            }

            // Get parent directory
            val parent = currentDir.parent

            // If no parent directory (reached root), throw exception
            if (parent == null) {
                logger.error { "Failed to find $partial - reached filesystem root" }
                throw RuntimeException("Failed to find $partial in any parent directory")
            }

            // Move to parent directory
            currentDir = parent
        }

        // This should never be reached due to the check inside the loop
        throw RuntimeException("Unexpected error during file search")
    }
}

class IndefiniteWaitOneShotWaitStrategy : AbstractWaitStrategy() {
    override fun waitUntilReady() {
        check(
            IndefiniteWaitOneShotStartupCheckStrategy().waitUntilStartupSuccessful(
                waitStrategyTarget.dockerClient,
                waitStrategyTarget.containerId,
            ),
        ) { waitStrategyTarget.getLogs(OutputFrame.OutputType.STDERR) }
    }
}
