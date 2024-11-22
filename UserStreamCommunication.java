
import java.util.concurrent.CompletableFuture;

import com.sun.net.httpserver.HttpExchange;

public interface UserStreamCommunication {

	void ask(String prompt, String callSid, HttpExchange he, boolean isReport, boolean train, Object o);

}