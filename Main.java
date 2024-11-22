import java.time.Duration;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

import com.sun.net.httpserver.HttpExchange;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

public class Main implements UserStreamCommunication, ModelCommunication {
	    private static StreamingChatLanguageModel languageModel;
	    private static ModelCommunication assistant;
	    private static Scanner scanner;
	    
	    public static void main(String[] args) {
	    	scanner = new Scanner(System.in);
	    	String s =  "You are a debt collection agent for a company called CALM RECOVERIES. Your name is Hellen. You will be trained on several models. Training begins with keyword TRAINING MODELS followed by a list of models. A model contains a model_id, title, scope, mood, tone, attitude, audience. You will handle multiple conversations concurrently. A new conversation begins with keyword NEW CONVERSATION. It will contain the conversation_id, model_id and user_profile. A prompt will contain the conversation_id to uniquely identify the conversation a prompt belongs to and user_input. TRAINING MODELS model_id : 1, scope : You wll be provided with user profile, start a conversation with the user and ask the user to pay the loan. Write all your statements for model_id : 1 in this format conversation_id : id, text : text. When generating reports for conversation, write your statements in this format conversation_id : id, report : report. End conversations with have a good day. If you understand respond with understood";
			Main main = new Main("http://localhost:11434", "gemma2:2b");
			main.ask(s, null, null, false, false, null);
			String prompt;
			while(true) {
				prompt = scanner.nextLine();
				main.ask(prompt, null, null, false, false, null);
			}
	    }

	    public Main(String modelUrl, String modelName) {
	        this.languageModel = connectModel(modelUrl, modelName);
	        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);
	        this.assistant = AiServices.builder(ModelCommunication.class)
	                .streamingChatLanguageModel(this.languageModel)
	                .chatMemory(chatMemory)
	                .build();
	    }

	    public void ask(String userPrompt, String callSid, HttpExchange he, boolean isReport, boolean train, Object obj) {
	        TokenStream tokenStream = chatWithModel(userPrompt);
	        CompletableFuture<Void> future = new CompletableFuture<>();
	        tokenStream.onNext(System.out::print);
	        tokenStream.onComplete(resp-> {
	                    future.complete(null);
	                })
	                .onError(Throwable::printStackTrace)
	                .start();
	    }
	    
	    private StreamingChatLanguageModel connectModel(String url, String modelName) {
	        return OllamaStreamingChatModel.builder()
	                .baseUrl(url)
	                .modelName(modelName)
	                .timeout(Duration.ofHours(1))
	                .build();
	    }

		@Override
		public TokenStream chatWithModel(String message) {
			return assistant.chatWithModel(message);
		}
}