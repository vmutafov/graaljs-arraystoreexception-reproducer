import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import org.graalvm.polyglot.*
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.io.InputStream
import java.nio.charset.StandardCharsets

private val engine = Engine
    .newBuilder()
    .allowExperimentalOptions(true)
    .option("js.ecmascript-version", "staging")
    .option("engine.WarnInterpreterOnly", "false")
    .option("js.esm-eval-returns-exports", "true")
    .option("js.interop-complete-promises", "true")
    .option("js.foreign-object-prototype", "true")
    .build()

private const val script = """
   proxyFun((a, b) => {
    console.log("!!! VM: a + b: " + (a + b));
   }, 1000, 2000);
"""
private val sourceStream: InputStream = script.byteInputStream(StandardCharsets.UTF_8)
private val source = Source.newBuilder("js", sourceStream.reader(StandardCharsets.UTF_8), "test.js").build()

fun main() {
    val vertx = Vertx.vertx()
    vertx.deployVerticle({
        MyVerticle()
    }, DeploymentOptions().setInstances(8))
}

class MyVerticle : AbstractVerticle() {
    override fun start() {
        super.start()
        createContext(vertx).eval(source)
    }
}

private fun createContext(vertx: Vertx): Context {
    val context = Context
        .newBuilder()
        .engine(engine)
        .allowEnvironmentAccess(EnvironmentAccess.INHERIT)
        .allowExperimentalOptions(true)
        .option("js.interop-complete-promises", "true")
        .allowHostClassLookup { true }
        .allowHostAccess(HostAccess.ALL)
        .allowAllAccess(true)
        .allowCreateThread(true)
        .allowIO(true)
        .allowCreateProcess(true)
        .build()

    context.getBindings("js").putMember("proxyFun", ProxyFun(vertx))
    return context
}

class ProxyFun(private val vertx: Vertx) : ProxyExecutable {
    override fun execute(vararg arguments: Value?): Any? {
        val fn = arguments[0]
        val args = arguments.slice(1 until arguments.size).toTypedArray()

//        val nonCrashyTask = { fn!!.executeVoid(*copied(args)) }
        val crashyTask = { fn!!.executeVoid(*args) }

        // Only running with `runOnContext` fails even though the function passed to it
        // is executed on the same thread according to https://vertx.io/docs/apidocs/io/vertx/core/Context.html
        vertx.runOnContext {
            crashyTask() // this crashes once or twice when running all verticles
//            nonCrashyTask() // this does not crash, at least from what I've tried
        }

        // If the following is ran instead of with `runOnContext`, it doesn't crash
//        task() // this does not crash
        return null
    }

}

private fun copied(arguments: Array<Value?>): Array<Value?> {
    val o = arrayOfNulls<Value?>(arguments.size)
    System.arraycopy(arguments, 0, o, 0, o.size)
    return o
}