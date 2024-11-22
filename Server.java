

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.twilio.Twilio;
import com.twilio.http.HttpMethod;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Say;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

public class Server implements Commands, Settings {
	public static final String TWILIIO_ACCOUNT_SID = "accountSid";
    public static final String TWILIO_AUTH_TOKEN = "authToken";
	private final static String OLLAMA_HOST = "http://localhost:11434";
	private final static String AI_MODEL = "gemma2:2b";
	private final static String FROM_EMAIL = "fromEmail";
	private static boolean running = false;
	private static String conversationIdSelected = null;
	private static ServerSocket ss;
	private static CustomQueue<ScheduledCall> scheduledCalls = new CustomQueue<>();
	private static CustomQueue<ScheduledSms> scheduledSms = new CustomQueue<>();
	private static CustomQueue<ScheduledEmail> scheduledEmail = new CustomQueue<>();
	private static CustomQueue<OngoingCall> ongoingCalls = new CustomQueue<>();
	private static final String twilioMobile = "twilioMobile";
	private static final String serverURL = "serverUrl";
	private static final ChatService chatService = new ChatService(OLLAMA_HOST, AI_MODEL);
	
	public static void main(String[] args) {
		Twilio.init(TWILIIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN);
		TrainAI.train();
		IVRServer.runHttpServer();
		viewDatabase();
		createDatabase();
		createDatabaseTables();
		try {
			ss = new ServerSocket(8000);
			running = true;
			System.out.println("Server started");
			new AutomationThread().start();
			refreshCalls();
			Socket s;
			while(running) {
				try {
					s = ss.accept();
					new SocketSwitch(s);
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
		}catch(Exception ex) {
			ex.printStackTrace();
			running = false;
			try {
				ss.close();
			}catch(Exception e) {
				
			}
		}
	}
	
	static class ChatService implements UserStreamCommunication, ModelCommunication {

	    private final StreamingChatLanguageModel languageModel;
	    private final ModelCommunication assistant;
	    private boolean wait = true;
	    private CustomQueue<Object[]> queue = new CustomQueue<>();
	    private Thread thrd =  null;
	    private boolean thrdStarted = false;

	    public ChatService(String modelUrl, String modelName) {
	        this.languageModel = connectModel(modelUrl, modelName);
	        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);
	        this.assistant = AiServices.builder(ModelCommunication.class)
	                .streamingChatLanguageModel(this.languageModel)
	                .chatMemory(chatMemory)
	                .build();
	    }

	    public void ask(String userPrompt, String callSid, HttpExchange he, boolean isReport, boolean train, Object o) {
	    	if(thrd == null) {
	    		thrd = new Thread(()->{
	    			if(!thrdStarted) {
	    				thrdStarted = true;
	    				wait = false;
	    			}
		    		 while(running) {
		 	        	if(!wait) {
		 	        		wait = true;
		 	        		Object obj[] = queue.removeLast();
		 	        		ask(obj);
		 	        	}
		 	        }
		    	});
	    		thrd.start();
	    	}
	    	queue.add(new Object[] {userPrompt, callSid, he, isReport, train, o});
	    }
	    
	    private void ask(Object[] obj) {
	    	TokenStream tokenStream = chatWithModel((String)obj[0]);
 	        CompletableFuture<Void> future = new CompletableFuture<>();
 	        tokenStream.onNext(e->{
 	        	
 	        });
 	        tokenStream.onComplete(resp-> {
 	                    future.complete(null);
 	                    wait = false;
 	                    handleResponse(resp, (String)obj[1], (HttpExchange)obj[2], (boolean)obj[3], (boolean)obj[4], obj[5]);
 	                })
 	                .onError(error->{
 	                	error.printStackTrace();
 	                	wait = false;
 	                })
 	                .start();
	    }
	    
	    private static void handleResponse(dev.langchain4j.model.output.Response<AiMessage> resp, String callSid, HttpExchange he, boolean isReport, boolean train, Object o) {
	    	 String txt = resp.content().text();
	    	 System.out.println(txt);
	    	 if(!isReport && !train && o == null) {
	    		 txt = txt.substring(txt.indexOf("text:") + 5, txt.indexOf("toolExcecutionRequest")).trim();
		    	 txt = txt.substring(1, txt.length());
		    	 System.out.println(txt);
		    	 String route = "/conversation/" + callSid; 
		    	 boolean endCall = txt.contains("Have a good day");
		    	 if(endCall) {
		    		 route = "/endCall/" + callSid;
		    	 }
	    		 Say say = new Say.Builder(txt).build();
	    	  	 Gather gather = new Gather.Builder().action(route).build();
	    	  	 VoiceResponse vr = new VoiceResponse.Builder().gather(gather).say(say).build();
	    	  	 try {
	    	  		he.sendResponseHeaders(200, vr.toString().length());
	        	    OutputStream os = he.getResponseBody();
	        	    os.write(vr.toString().getBytes("UTF-8"));
	        	    os.close();
	    	  	 }catch(Exception ex) {
	    	  		 ex.printStackTrace();
	    	  	 }
		    	 try {
		             if(isReport) {
		            	//add report to database
		            	Connection dbConn = getDatabaseConnection();
		            	PreparedStatement pstmnt = dbConn.prepareStatement("UPDATE conversation SET report = ? WHERE id = ?");
		            	pstmnt.setString(1, txt);
		            	pstmnt.setString(2, callSid);
		            	pstmnt.execute();
		            	//send report to client ui
		            	Report report = new Report(4, -1, callSid, txt, null, new Date().getTime());
		            	SocketHandler.outgoingRequests.add(new Request1(REPORT, report));
		             }else if(!train && !isReport) {
		            	//add speech-to-text to database
		         	 	Connection dbConn = getDatabaseConnection();
		 	        	PreparedStatement pstmnt = dbConn.prepareStatement("INSERT INTO stt (call_sid, sender, timestamp, text) VALUES (?, ?, ?, ?) RETURNING id");
		 	        	pstmnt.setString(1, callSid);
		 	        	pstmnt.setInt(2, 1); //1 for machine 2 for human
		 	        	pstmnt.setString(3, new Date().toString());
		 	        	pstmnt.setString(4, txt);
		 	        	ResultSet rset = pstmnt.executeQuery();
		 	        	rset.next();
		 	        	int sttId = rset.getInt(1);
		 	        	pstmnt.close();
		 	        	dbConn.close();
		 	        	//send STT to client UI
			        	STT stt = new STT(sttId, callSid, 1, txt, new Date().getTime());
			        	SocketHandler.outgoingRequests.add(new Request1(STT, stt));
		             }
	        	 }catch(Exception ex) {
	        		 ex.printStackTrace();
	        	 }
	    	 }else if (o != null) {
	    			 //send that sms here to the user and add to database
	    			 String text = "";
	    			 String subject = "";
	    			 int accId, type; String mobile; long tst = new Date().getTime();
	    			 Sms sms = null; Email em = null;
	    			 if(o instanceof ScheduledSms) {
	    				 ScheduledSms sS = (ScheduledSms)o;
	    				 mobile = sS.acc.mobile;
	    				 accId = sS.acc.id;
	    				 sms = new Sms(text, sS.acc.mobile);
	    				 type = 1;
	    			 }else {
	    				 ScheduledEmail sE = (ScheduledEmail)o;
	    				 em = new Email(text, subject, sE.acc.mobile);
	    				 type = 2;
	    				 mobile = sE.acc.mobile;
	    				 accId = sE.acc.id;
	    			 }
	    			 
	    			 try {
	    				 Connection dbCon = getDatabaseConnection();
	    				 PreparedStatement pstmnt = dbCon.prepareStatement("INSERT INTO messages (account_id, mobile, type, subject, text, timestamp, status) VALUES (?, ?, ?, ?, ?, ?, ?))");
	    				 pstmnt.setInt(1, accId);
	    				 pstmnt.setString(2, mobile);
	    				 pstmnt.setInt(3, type);
	    				 pstmnt.setString(4, subject);
	    				 pstmnt.setString(5, txt);
	    				 pstmnt.setLong(6, tst);
	    				 pstmnt.setString(7, "PENDING");
	    				 pstmnt.execute();
	    				 pstmnt.close();
	    				 if(sms != null) {
	    					 Server.sendSms(sms);
	    				 }else {
	    					 Server.sendMail(twilioMobile, em.mobile, subject, text);
	    				 }
	    				 
	    				 pstmnt = dbCon.prepareStatement("UPDATE messages SET status = 'SUCCESS' WHERE timestamp = ?");
	    				 pstmnt.setLong(1, tst);
	    				 pstmnt.execute();
	    				 pstmnt.close();
	    				 dbCon.close();
	    			 }catch(Exception ex) {
	    				 try {
	    					 Connection conn = getDatabaseConnection();
	    					 PreparedStatement pstmnt = conn.prepareStatement("UPDATE messages SET status = 'FAILED' WHERE timestamp = ?");
	    					 pstmnt.setLong(1, tst);
		    				 pstmnt.execute();
		    				 pstmnt.close();
		    				 conn.close();
	    				 }catch(Exception ex1) {
	    					 ex1.printStackTrace();
	    				 }
    			    }
    		 }
	    	
	    }

	    @Override
	    public TokenStream chatWithModel(String message) {
	        return assistant.chatWithModel(message);
	    }
	    
	    private static StreamingChatLanguageModel connectModel(String url, String modelName) {
	        return OllamaStreamingChatModel.builder()
	                .baseUrl(url)
	                .modelName(modelName)
	                .timeout(Duration.ofHours(1))
	                .build();
	    }
	}
	
	private static void createDatabase() {
		try {
			String className = "org.postgresql.Driver";
			String dbUrl = "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=xxxxxx";
			Class.forName(className);
			Connection conn = DriverManager.getConnection(dbUrl, "super", "xxxxxxxx");
			Statement stmnt = conn.createStatement();
			stmnt.execute("CREATE DATABASE complexity_ai_db");
			stmnt.close();
			conn.close();
			System.out.println("Database created");
		}catch(Exception ex) {
			//database already exists
		}
	}
	
	private static void viewDatabase() {
		try {
			String className = "org.postgresql.Driver";
			String dbUrl = "jdbc:postgresql://localhost:5432/complexity_ai_db?user=postgres&password=wallace1997";
			Class.forName(className);
			Connection conn = DriverManager.getConnection(dbUrl, "super", "xxxxxxxxxx");
			Statement stmnt = conn.createStatement();
			stmnt.execute("DROP TABLE payments");
			stmnt.close();
			conn.close();
			System.out.println("Database dropped");
		}catch(Exception ex) {
			ex.printStackTrace();
		}
	}
	
	private static Connection getDatabaseConnection() throws SQLException, ClassNotFoundException {
		String className = "org.postgresql.Driver";
		String dbUrl = "jdbc:postgresql://localhost:5432/complexity_ai_db?user=postgres&password=xxxxxxx";
		Class.forName(className);
		return DriverManager.getConnection(dbUrl, "super", "xxxxxxx");
	}
	
	
	private static void createDatabaseTables() {
		try {
			Connection conn = getDatabaseConnection();
			Statement stmnt = conn.createStatement();
			stmnt.execute("CREATE TABLE IF NOT EXISTS ai_settings(sentimental_analysis BOOLEAN NOT NULL DEFAULT 'FALSE', personalized_engagement BOOLEAN NOT NULL DEFAULT 'FALSE', automated_payment_plans BOOLEAN NOT NULL DEFAULT 'FALSE', dispute_resolution BOOLEAN NOT NULL DEFAULT 'FALSE')");
			stmnt.execute("CREATE TABLE IF NOT EXISTS accounts(id SERIAL PRIMARY KEY, fullname VARCHAR NOT NULL, id_number VARCHAR NOT NULL, employment_status VARCHAR NOT NULL, monthly_income DOUBLE PRECISION NOT NULL, mobile VARCHAR NOT NULL, email VARCHAR NOT NULL, acc_group VARCHAR NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS loans(id SERIAL PRIMARY KEY, account_id INTEGER NOT NULL, amount DOUBLE PRECISION NOT NULL, date_issued BIGINT NOT NULL, date_due BIGINT NOT NULL, paid_amount DOUBLE PRECISION DEFAULT '0')");
			stmnt.execute("CREATE TABLE IF NOT EXISTS messages(id SERIAL PRIMARY KEY, account_id INTEGER NOT NULL, type INTEGER NOT NULL, subject VARCHAR, text VARCHAR NOT NULL, timestamp BIGINT NOT NULL, status VARCHAR NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS conversation (id SERIAL PRIMARY KEY, account_id INTEGER NOT NULL, call_sid VARCHAR NOT NULL, phone_id VARCHAR NOT NULL, direction INTEGER NOT NULL, timestamp BIGINT NOT NULL, status VARCHAR NOT NULL, duration INTEGER, report VARCHAR)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS stt (id SERIAL PRIMARY KEY, call_sid VARCHAR NOT NULL, sender INTEGER NOT NULL, timestamp BIGINT NOT NULL, text VARCHAR NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS reports (id SERIAL PRIMARY KEY, call_sid VARCHAR NOT NULL, brief VARCHAR NOT NULL, details VARCHAR NOT NULL, timestamp BIGINT NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS models(id SERIAL PRIMARY KEY, model_name VARCHAR NOT NULL, instructions VARCHAR NOT NULL, mood VARCHAR NOT NULL, tone VARCHAR NOT NULL, attitude VARCHAR NOT NULL, audience VARCHAR NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS scope_files(id SERIAL PRIMARY KEY, model_id INTEGER NOT NULL, file_name VARCHAR NOT NULL, instructions VARCHAR NOT NULL, file_path VARCHAR NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS campaigns(id SERIAL PRIMARY KEY, title VARCHAR NOT NULL, model_id INTEGER NOT NULL, account_group VARCHAR NOT NULL, schedule_time BIGINT NOT NULL, call BOOLEAN NOT NULL, sms BOOLEAN NOT NULL, done BOOLEAN DEFAULT 'false')");
			stmnt.execute("CREATE TABLE IF NOT EXISTS reminders(id SERIAL PRIMARY KEY, title VARCHAR NOT NULL, model_id INTEGER NOT NULL, caLL BOOLEAN NOT NULL, sms BOOLEAN NOT NULL, email BOOLEAN NOT NULL, days_past_due INTEGER NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS final_notices(id SERIAL PRIMARY KEY, title VARCHAR NOT NULL, model_id INTEGER NOT NULL, caLL BOOLEAN NOT NULL, sms BOOLEAN NOT NULL, email BOOLEAN NOT NULL, days_past_due INTEGER NOT NULL)");
			stmnt.execute("CREATE TABLE IF NOT EXISTS payments(id SERIAL PRIMARY KEY, account_id INTEGER NOT NULL, transaction_code VARCHAR NOT NULL, timestamp BIGINT NOT NULL, amount DOUBLE PRECISION NOT NULL)");
			stmnt.close();
			conn.close();
			System.out.println("Database tables created");
		}catch(Exception ex) {
			ex.printStackTrace();
		}
	}
	
	private static void sendMail(String from, String to, String subject, String text) throws Exception{
		Properties prop = System.getProperties();
		prop.setProperty("mail.smtp.host", "localhost");
		Session session = Session.getDefaultInstance(prop);
		try {
			MimeMessage message = new MimeMessage(session);
			message.setFrom(new InternetAddress(from));
			message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
			message.setSubject(subject);
			message.setText(text);
			Transport.send(message);
		}catch(MessagingException ex) {
			ex.printStackTrace();
			throw ex;
		}
	}
	
	
	private static class TrainAI {
		
		static void train() {
			String s =  "You are a debt collection agent for a company called CALM RECOVERIES. Your name is Hellen. You will be trained on several models. Training begins with keyword TRAINING MODELS followed by a list of models. A model contains a model_id, title, scope, mood, tone, attitude, audience. You will handle multiple conversations concurrently. A new conversation begins with keyword NEW CONVERSATION. It will contain the conversation_id, model_id and user_profile. A prompt will contain the conversation_id to uniquely identify the conversation a prompt belongs to and user_input. TRAINING MODELS model_id : 1, scope : You wll be provided with user profile, start a conversation with the user and ask the user to pay the loan. Write all your statements for model_id : 1 in this format conversation_id : id, text : text. When generating reports for conversation, write your statements in this format conversation_id : id, report : report. End conversations with have a good day. If you understand respond with understood";
			s += getModelsInstructions();
        	chatService.ask(s, null, null, false, true, null);
		}
		
		private static String getModelsInstructions() {
			String s = null;
			try {
				Connection conn = getDatabaseConnection();
				Statement stmnt = conn.createStatement();
				ResultSet rset = stmnt.executeQuery("SELECT id, model_name, instructions, mood, tone, attitude, audience FROM models");
				while(rset.next()) {
					if(s == null) {
						s += "TRAINING MODELS /n/n";
					}
					s += " Model id : " + rset.getInt(1) + ", ";
					s += "Title : " + rset.getString(2) + ", ";
					s += "Scope : " + rset.getString(3) + ", ";
					s += "Mood : " + rset.getString(4) + ", ";
					s += "Tone : " + rset.getString(5) + ", ";
					s += "Attitude : " + rset.getString(6) + ", ";
					s += "Audience : " + rset.getString(7) + ".";
				}
				s += "If you have understood, respond with understood";
				stmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
			return s;
		}
	}
	
	
	private static class AutomationThread extends Thread {
		private CustomQueue<Object> autos = new CustomQueue<>();
		
		@Override
		public void run() {
			new RunAutos().start();
			new CommunicationAutomation().start();
			while(running) {
				try {
					Connection conn = getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement("SELECT id, title, model_id, account_group, call, sms, email FROM campaigns WHERE schedule_time <= ? AND done = ?");
					pstmnt.setLong(1, new Date().getTime());
					pstmnt.setBoolean(2, false);
					ResultSet rset = pstmnt.executeQuery();
					Campaign c;
					Campaign cm;
					while(rset.next()) {
						c = new Campaign(rset.getInt(1), rset.getString(2), rset.getInt(3), rset.getString(4), rset.getBoolean(5), rset.getBoolean(6), rset.getBoolean(7));
						boolean available = false;
						for(Object ob : autos) {
							if(ob instanceof Campaign) {
								cm = (Campaign)ob;
								if(c.id == cm.id) {
									available = true;
									break;
								}
							}
						}
						if(!available) {
							System.out.println("New Campaign added to communication automation");
							autos.offer(c);
						}
					}
					
					//select reminders
					pstmnt = conn.prepareStatement("SELECT id, title, model_id, account_group, call, sms, email, days_past_due FROM reminders");
					rset = pstmnt.executeQuery();
					Reminder r;
					Reminder rm;
					while(rset.next()) {
						r = new Reminder(rset.getInt(1), rset.getString(2), rset.getInt(3), rset.getString(4), rset.getBoolean(5), rset.getBoolean(6), rset.getBoolean(7), rset.getInt(8));
						boolean available = false;
						for(Object ob : autos) {
							if(ob instanceof Reminder) {
								rm = (Reminder)ob;
								if(r.id == rm.id) {
									available = true;
									break;
								}
							}
						}
						if(!available) {
							autos.offer(r);
						}
					}
					
					//select final notices
					pstmnt = conn.prepareStatement("SELECT id, title, model_id, account_group, call, sms, email, days_past_due FROM final_notices");
					rset = pstmnt.executeQuery();
					FinalNotice f;
					FinalNotice fm;
					while(rset.next()) {
						f = new FinalNotice(rset.getInt(1), rset.getString(2), rset.getInt(3), rset.getString(4), rset.getBoolean(5), rset.getBoolean(6), rset.getBoolean(7), rset.getInt(8));
						boolean available = false;
						for(Object ob : autos) {
							if(ob instanceof FinalNotice) {
								fm = (FinalNotice)ob;
								if(f.id == fm.id) {
									available = true;
									break;
								}
							}
						}
						if(!available) {
							autos.offer(f);
						}
					}
					pstmnt.close();
					conn.close();
				}catch(Exception ex) {
					ex.printStackTrace();
				}
				try {
					Thread.sleep(60000);
				}catch(InterruptedException ie) {
					
				}
			}
		}
		
		private class CommunicationAutomation extends Thread {
			
			CommunicationAutomation(){
				new CallHandler().start();
				new SmsHandler().start();
				new EmailHandler().start();
			}
			
			
			private class CallHandler extends Thread {
				private final int maxCalls = 10;
				private final int deltaTime = 1000;
				
				CallHandler(){
					System.out.println("Call handler started");
					while(running) {
						if(ongoingCalls.size() < maxCalls && scheduledCalls.size() > 0) {
							makeCall(scheduledCalls.poll());
						}
						try {
							Thread.sleep(deltaTime);
						}catch(InterruptedException ex) {
							
						}
					}
				}
				private void makeCall(ScheduledCall sC) {
					Call1 c1 = new Call1((sC.isBot) ? sC.sid : new Date().getTime() + "", sC.acc.mobile, 1, Long.parseLong(sC.sid), null);
					String report = "";
					/*
					try {
						Call call = Call.creator(new com.twilio.type.PhoneNumber(sC.acc.mobile), new com.twilio.type.PhoneNumber(twilioMobile), URI.create(serverURL + "/hello"))
								.setMethod(HttpMethod.GET)
								.setStatusCallback(URI.create(serverURL + "/status"))
								.setStatusCallbackEvent(Arrays.asList("initiated", "ringing", "answered", "completed", "busy", "canceled", "failed", "in_progress", "no_answer", "queued"))
								.setStatusCallbackMethod(HttpMethod.GET)
								.create();
						c1 = new Call1(call.getSid(), sC.acc.mobile, 1, new Date().getTime(), call.getStatus().toString());
						ongoingCalls.add(new OngoingCall(sC, c1));
						
						if(sC.isBot) {
							SocketHandler.outgoingRequests.add(new Request1(CALL_SID, new Object[] {c1.call_sid, sC.sid}));
						}
						
					}catch(Exception ex) {
						report = "Call failed. Conversation not held";
						c1.status = "failed";
					}
					*/
					//make dummy call
					String s = "Hello, Am Hellen from calm recoveries. This is a reminder to remind you to clear you loan of " + sC.loan.amount + " by the end of the week. Have a good day";
					Twilio.init(TWILIIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN);
					String to = "toMobile";  
					Call call = Call.creator(new com.twilio.type.PhoneNumber(to), new com.twilio.type.PhoneNumber(twilioMobile), new com.twilio.type.Twiml("<Response><Say>" + s + "</Say></Response>")).create();
					c1 = new Call1(call.getSid(), sC.acc.mobile, 1, new Date().getTime(), call.getStatus().toString());
					
					new Thread(()->{
						boolean cont = true;
						while(cont) {
							Call call1 = Call.creator(new com.twilio.type.PhoneNumber(to), new com.twilio.type.PhoneNumber(twilioMobile), call.getSid()).create();
							//notify client UI
							Call1 c2 = new Call1(call1.getSid(), sC.acc.mobile, 1, new Date().getTime(), call1.getStatus().toString());
							SocketHandler.outgoingRequests.add(new Request1(CALL, c2));
							if(call1.getStatus().toString().equalsIgnoreCase(Call.Status.COMPLETED.toString()) || call1.getStatus().toString().equalsIgnoreCase(Call.Status.CANCELED.toString()) || (call1.getStatus().toString().equalsIgnoreCase(Call.Status.FAILED.toString())) || (call1.getStatus().toString().equalsIgnoreCase(Call.Status.NO_ANSWER.toString())) || (call1.getStatus().toString().equalsIgnoreCase(Call.Status.BUSY.toString()))) {
								cont = false;
							}
							try {
								Connection conn = getDatabaseConnection();
				    	  		PreparedStatement pstmnt = conn.prepareStatement("UPDATE conversation SET status = ? WHERE call_sid = ?");
				    	  		pstmnt.setString(1, call1.getSid());
				    	  		pstmnt.setString(2, call1.getStatus().toString().toUpperCase());
				    	  		pstmnt.execute();
								Thread.sleep(1000);
							}catch(Exception ex) {
								ex.printStackTrace();
							}
						}
					}).start();
					
					
					//add conversation to database
					try {
						Connection dbConn = getDatabaseConnection();
			        	PreparedStatement pstmnt = dbConn.prepareStatement("INSERT INTO conversation (account_id, call_sid, phone_id, direction, timestamp, status, report) VALUES (?, ?, ?, ?, ?, ?, ?)");
			        	pstmnt.setInt(1, sC.accId);
			        	pstmnt.setString(2, c1.call_sid);
			        	pstmnt.setString(3, sC.acc.mobile);
			        	pstmnt.setInt(4, 1);
			        	long dt = new Date().getTime();
			        	pstmnt.setLong(5, dt);
			        	pstmnt.setString(6, c1.status.toUpperCase());
			        	pstmnt.setString(7, report);
			        	pstmnt.execute();
					}catch(Exception ex) {
						ex.printStackTrace();
					}
		    	  	
					//notify client UI
					SocketHandler.outgoingRequests.add(new Request1(CALL, c1));
				}
			}
			
			private class SmsHandler extends Thread {
				private final int deltaTime = 50;
				
				SmsHandler(){
					while(running) {
						if(scheduledSms.size() > 0) {
							sendSms(scheduledSms.poll());
						}
						try {
							Thread.sleep(deltaTime);
						}catch(InterruptedException ex) {
							
						}
					}
				}
			}
			
			private void sendSms(ScheduledSms sS) {
				String s = "NEW SMS model_id : " + sS.modelId + ", user_profile : name - " + sS.acc.name + ", loan_amount - " + sS.loan.amount;
				chatService.ask(s, null, null, false, false, sS);
			}
			
			
			private class EmailHandler extends Thread {
				private final int deltaTime = 50;
				
				EmailHandler(){
					while(running) {
						if(scheduledEmail.size() > 0) {
							sendEmail(scheduledEmail.poll());
						}
						try {
							Thread.sleep(deltaTime);
						}catch(InterruptedException ex) {
							
						}
					}
				}
			}
			
			private void sendEmail(ScheduledEmail sE) {
				String s = "NEW EMAIL model_id : " + sE.modelId + ", user_profile : name - " + sE.acc.name + ", loan_amount - " + sE.loan.amount; 
				chatService.ask(s, null, null, false, false, sE);
			}
			
		}
		
		
		private class RunAutos extends Thread {
			
			@Override
			public void run() {
				Object ob;
				int account_id;
				long dateIssued, dateDue;
				double amount, paidAmount;
				String fullname, mobile;
				Account acc;
				Loan loan;
				int modelId, templateType;
				String accGroup;
				boolean call, sms, email;
				while(running) {
					ob = autos.poll();
					if(ob instanceof Campaign) {
						templateType = 1;
						Campaign cP = (Campaign)ob;
						modelId = cP.modelId;
						accGroup = cP.accGroup;
						call = cP.call;
						sms = cP.sms;
						email = cP.email;
						//select all accounts under the Account Group
						try {
							Connection conn = getDatabaseConnection();
							PreparedStatement pstmnt = conn.prepareStatement("SELECT id, fullname, mobile FROM accounts WHERE acc_group = ?");
							pstmnt.setString(1, accGroup);
							ResultSet rset = pstmnt.executeQuery();
							PreparedStatement pstmnt1;
							ResultSet rset1;
							while(rset.next()) {
								account_id = rset.getInt(1);
								fullname = rset.getString(2);
								mobile = rset.getString(3);
								acc = new Account(account_id, fullname, mobile);
								pstmnt1 = conn.prepareStatement("SELECT amount, date_due, date_issued, paid_amount FROM loans WHERE account_id = ? AND amount < paid_amount");
								pstmnt1.setInt(1, account_id);
								rset1 = pstmnt1.executeQuery();
								if(rset1.next()) {
									amount = rset1.getDouble(1);
									dateDue = rset1.getLong(2);
									dateIssued = rset1.getLong(3);
									paidAmount = rset.getDouble(4);
									loan = new Loan(account_id, amount, dateDue, dateIssued, paidAmount);
									if(call) {
										scheduledCalls.offer(new ScheduledCall(templateType, acc, loan, modelId));
									}
									if(sms) {
										scheduledSms.offer(new ScheduledSms(templateType, acc, loan, modelId));
									}
									if(email) {
										scheduledEmail.offer(new ScheduledEmail(templateType, acc, loan, modelId));
									}
								}
							}
							pstmnt1 = conn.prepareStatement("UPDATE campaigns SET done = ? WHERE id = ?");
							pstmnt1.setBoolean(1, true);
							pstmnt1.setInt(2, cP.id);
							pstmnt1.execute();
							conn.close();
						}catch(Exception ex) {
							ex.printStackTrace();
						}
					}else {
						int daysPastDue;
						if(ob instanceof Reminder) {
							templateType = 2;
							Reminder rM = (Reminder)ob;
							modelId = rM.modelId;
							accGroup = rM.accGroup;
							call = rM.call;
							sms = rM.sms;
							email = rM.email;
							daysPastDue = rM.daysPastDue;
						}else {
							templateType = 3;
							FinalNotice fN = (FinalNotice)ob;
							modelId = fN.modelId;
							accGroup = fN.accGroup;
							call = fN.call;
							sms = fN.sms;
							email = fN.email;
							daysPastDue = fN.daysPastDue;
						}
						try {
							Connection conn = getDatabaseConnection();
							long startTime = new Date().getTime() + (daysPastDue * 24 * 60 * 60 * 1000);
							long endTime = startTime + (24 * 60 * 60 * 1000);
							PreparedStatement pstmnt = conn.prepareStatement("SELECT loans.amount, loans.date_due, loans.date_issued, loans.paid_amount, accounts.id, accounts.fullname, accounts.mobile FROM loans INNER JOIN accounts ON accounts.id = loans.account_id WHERE loans.date_due >= ? AND loans.date_due <= ?");
							pstmnt.setLong(1, startTime);
							pstmnt.setLong(2, endTime);
							ResultSet rset = pstmnt.executeQuery();
							while(rset.next()) {
								amount = rset.getDouble(1);
								dateDue = rset.getInt(2);
								dateIssued = rset.getInt(3);
								paidAmount = rset.getDouble(4);
								account_id = rset.getInt(5);
								fullname = rset.getString(6);
								mobile = rset.getString(7);
								acc = new Account(account_id, fullname, mobile);
								loan = new Loan(account_id, amount, dateDue, dateIssued, paidAmount);
								if(call) {
									scheduledCalls.offer(new ScheduledCall(templateType, acc, loan, modelId));
								}
								if(sms) {
									scheduledSms.offer(new ScheduledSms(templateType, acc, loan, modelId));
								}
								if(email) {
									scheduledEmail.offer(new ScheduledEmail(templateType, acc, loan, modelId));
								}
							}
							pstmnt.close();
							conn.close();
						}catch(Exception ex) {
							ex.printStackTrace();
						}
					}
				}
			}
		}
	}
	
	private static void sendSms(Sms sS) throws Exception {
		try {
			com.twilio.rest.api.v2010.account.Message msg = com.twilio.rest.api.v2010.account.Message.creator(new com.twilio.type.PhoneNumber(sS.mobile), new com.twilio.type.PhoneNumber(twilioMobile), sS.txt).create();				
		}catch(Exception ex) {
			throw ex;
		}
	}
	
	private static class SocketSwitch {
		
		SocketSwitch(Socket s){
			try {
				@SuppressWarnings("resource")
				int type = new DataInputStream(s.getInputStream()).readInt();
				switch(type) {
					case 2 : new AgentSocketHandler(s); break;
					default : new SocketHandler(s); break;
				}
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
	}
	
	private static class AgentSocketHandler extends Thread {
		private DataInputStream dis = null;
		private DataOutputStream dos = null;
		private boolean connected = false;
		private static List<Request1> outgoingRequests = Collections.synchronizedList(new LinkedList<Request1>());
		private Request1 req;
		
		AgentSocketHandler(Socket s) throws Exception {
			dis = new DataInputStream(s.getInputStream());
			dos = new DataOutputStream(s.getOutputStream());
			connected = true;
		}
		
		@Override
		public void run() {
			int command;
			try {
				while(connected) {
					req = outgoingRequests.remove(outgoingRequests.size() - 1);
					command = req.command;
					switch(command) {
						case CALL : invokeCall(); break;
					}
				}
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void invokeCall() throws IOException {
			dos.writeInt(CALL);
			dos.writeUTF((String)req.obj);
		}
	}
	
	
	private static class SocketHandler extends Thread {
		private int currentReportCursor = 0;
		private int currentHistoryCursor = 0;
		private int currentFilterCallsCursor = 0;
		private int currentFilterReportsCursor = 0;
		private int currentSearchReportsCursor = 0;
		private int currentSearchCallsCursor = 0;
		private DataInputStream dis = null;
		private DataOutputStream dos = null;
		private boolean connected = false;
		static List<Request1> outgoingRequests = Collections.synchronizedList(new LinkedList<Request1>());
		
		SocketHandler(Socket s) throws Exception {
			dis = new DataInputStream(s.getInputStream());
			dos = new DataOutputStream(s.getOutputStream());
			new OutgoingRequestsHandler(dos).start();
			connected = true;
			start();
		}
		
		@Override
		public void run() {
			int command;
			while(connected) {
				try { 
					command = dis.readInt();
					System.out.println("Incoming command " + command);
					switch(command) {
						case MAIL : {
							readMailRequest();
						}; break;
						case SMS : {
							readSmsRequest();
						}; break;
						case CALL : {
							readCallRequest();
						}; break;
						case RETRIEVE_ONGOING_CALLS : {
							outgoingRequests.add(new Request1(command, dis.readInt()));
						}; break;
						case RETRIEVE_ACC_SUMMARY : {
							outgoingRequests.add(new Request1(command, dis.readInt()));
						}; break;
						case UPLOAD_MODEL : uploadModel(); break;
						case ADD_CAMPAIGN : addCampaign(); break;
						case ADD_TEMPLATE : addTemplate(); break;
						case REFRESH_REPORTS : {
							outgoingRequests.add(new Request1(command, dis.readInt()));
						}; break;
						case RETRIEVE_STT : outgoingRequests.add(new Request1(command, dis.readUTF())); break;
						case FILTER_CALLS : {
							outgoingRequests.add(new Request1(command, new long[]{dis.readLong(), dis.readLong()}));
						}; break;
						case FILTER_REPORTS : {
							outgoingRequests.add(new Request1(command, new long[]{dis.readLong(), dis.readLong()}));
						}; break;
						case REFRESH_FILTER_CALLS : {
							outgoingRequests.add(new Request1(command, new Object[]{dis.readLong(), dis.readLong(), dis.readInt()}));
						}; break;
						case REFRESH_FILTER_REPORTS : {
							outgoingRequests.add(new Request1(command, new Object[]{dis.readLong(), dis.readLong(), dis.readInt()}));
						}; break;
						case REFRESH_SEARCH : {
							outgoingRequests.add(new Request1(command, dis.readUTF()));
						}; break;
						case SEARCH : {
							outgoingRequests.add(new Request1(command, new Object[] {dis.readInt(), dis.readUTF()}));
						}; break;
						case UPDATE_MODEL_DESIGNATOR : updateMDesignator(); break;
						case DELETE_MODEL : deleteModel(); break;
						case UPDATE_SETTINGS : updateSettings(); break;
						case AI_CONVO_REPORT : {
							sendAiConvoReport(dis.readUTF());
						}; break;
						case RETRIEVE_PAYMENT_HISTORY : {
							outgoingRequests.add(new Request1(command, dis.readInt()));
						}; break;
						case RETRIEVE_COMMUNICATION : {
							outgoingRequests.add(new Request1(command, dis.readInt()));
						}; break;
						case ACCOUNT  : {
							addAccount();
						}; break;
						case LOAN : {
							addLoan();
						}; break;
						case PAYMENT : {
							addPayment();
						}; break;
						default : outgoingRequests.add(new Request1(command, null)); break;
					}
				}catch(Exception ex) {
					ex.printStackTrace();
					connected = false;
				}
			}
		}
		
		private void addPayment() throws IOException {
			int accId = dis.readInt();
			String transId = dis.readUTF();
			double amount = dis.readDouble();
			long date = dis.readLong();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO payments (account_id, transaction_code, timestamp, amount) VALUES (?, ?, ?, ?)");
				pstmnt.setInt(1, accId);
				pstmnt.setString(2, transId);
				pstmnt.setLong(3, date);
				pstmnt.setDouble(4, amount);
				pstmnt.execute();
				
				//update loan belonging to this account
				pstmnt = conn.prepareStatement("SELECT paid_amount FROM loans WHERE account_id = ?");
				pstmnt.setInt(1, accId);
				ResultSet rset = pstmnt.executeQuery();
				rset.next();
				double amnt = rset.getDouble(1);
				amnt += amount;
				pstmnt = conn.prepareStatement("UPDATE loans SET paid_amount = ? WHERE account_id = ?");
				pstmnt.setDouble(1, amnt);
				pstmnt.setInt(2, accId);
				pstmnt.execute();
				pstmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void addLoan() throws IOException {
			int accId = dis.readInt();
			double amount = dis.readDouble();
			long dateIssued = dis.readLong();
			long dateDue = dis.readLong();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO loans (account_id, amount, date_due, date_issued) VALUES (?, ?, ?, ?)");
				pstmnt.setInt(1, accId);
				pstmnt.setDouble(2, amount);
				pstmnt.setLong(3, dateDue);
				pstmnt.setLong(4, dateIssued);
				pstmnt.execute();
				pstmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void addAccount() throws IOException {
			String fName = dis.readUTF();
			String idNo = dis.readUTF();
			String mobile = dis.readUTF();
			String email = dis.readUTF();
			double income = dis.readDouble();
			int empStatus = dis.readInt();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO accounts (fullname, id_number, employment_status, monthly_income, mobile, email, acc_group) VALUES (?, ?, ?, ?, ?, ?, ?)");
				pstmnt.setString(1, fName);
				pstmnt.setString(2, idNo);
				String s = "";
				if(empStatus == 1) {
					s = "Employed";
				}else if(empStatus == 2) {
					s = "Self Employed";
				}else {
					s = "Statudent";
				}
				pstmnt.setString(3, s);
				pstmnt.setDouble(4, income);
				pstmnt.setString(5, mobile);
				pstmnt.setString(6, email);
				pstmnt.setString(7, "A");
				pstmnt.execute();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void readMailRequest() throws IOException {
			int accId = dis.readInt();
			String to = dis.readUTF();
			String sbj = dis.readUTF();
			String txt = dis.readUTF();
			long tSt = dis.readLong();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO messages (account_id, type, text, timestamp, subject) VALUES (?, ?, ?, ?, ?)");
				pstmnt.setInt(1, accId);
				pstmnt.setInt(2, 2);
				pstmnt.setString(3, txt);
				pstmnt.setLong(4, tSt);
				pstmnt.setString(5, sbj);
				pstmnt.execute();
				pstmnt.close();
				sendMail(FROM_EMAIL, to, sbj, txt);
				pstmnt = conn.prepareStatement("UPDATE messages SET status = 'SUCCESS' WHERE timestamp = ?");
				pstmnt.setLong(1, tSt);
				pstmnt.execute();
				pstmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
				try {
					Connection conn = getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement("UPDATE messages SET status = 'FAILED' timestamp = ?");
					pstmnt.setLong(1, tSt);
					pstmnt.execute();
					pstmnt.close();
					conn.close();
				}catch(Exception ex1) {
					ex1.printStackTrace();
				}
			}
		}
		
		private void readSmsRequest() throws IOException {
			int accId = dis.readInt();
			String mobile = dis.readUTF();
			String txt = dis.readUTF();
			long tSt = dis.readLong();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO messages (account_id, type, text, timestamp, status) VALUES (?, ?, ?, ?, ?)");
				pstmnt.setInt(1, accId);
				pstmnt.setInt(2, 1);
				pstmnt.setString(3, txt);
				pstmnt.setLong(4, tSt);
				pstmnt.setString(5, "pending");
				pstmnt.execute();
				pstmnt.close();
				conn.close();
				Sms sms = new Sms(txt, mobile);
				sendSms(sms);
				pstmnt = conn.prepareStatement("UPDATE messages SET status = ? WHERE timestamp = ?");
				pstmnt.setString(1, "SUCCESS");
				pstmnt.setLong(2, tSt);
				pstmnt.execute();
				pstmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
				try {
					Connection conn = getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement("UPDATE messages SET status = ? WHERE timestamp = ?");
					pstmnt.setString(1, "FAILED");
					pstmnt.setLong(2, tSt);
					pstmnt.execute();
					pstmnt.close();
					conn.close();
				}catch(Exception ex1) {
					ex1.printStackTrace();
				}
			}
		}
		
		private void readCallRequest() throws IOException {
			int accId = dis.readInt();
			String sid = dis.readUTF();
			String phoneId = dis.readUTF();
			boolean isBot = dis.readBoolean();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("SELECT fullname FROM accounts WHERE id = ?");
				pstmnt.setInt(1, accId);
				ResultSet rset = pstmnt.executeQuery();
				rset.next();
				if(isBot) {
					Account a = new Account(accId, rset.getString(1), phoneId);
					pstmnt = conn.prepareStatement("SELECT amount, date_due, date_issued, paid_amount FROM loans WHERE account_id = ? AND paid_amount < amount");
					pstmnt.setInt(1, accId);
					rset = pstmnt.executeQuery();
					rset.next();
					Loan l = new Loan(accId, rset.getDouble(1), rset.getLong(2), rset.getLong(3), rset.getDouble(4));
					pstmnt = conn.prepareStatement("SELECT id FROM models");
					rset = pstmnt.executeQuery();
					rset.next();
					int id = rset.getInt(1);
					pstmnt.close();
					conn.close();
					ScheduledCall sC = new ScheduledCall(1, a, l, id);
					sC.accId = accId;
					sC.sid = sid;
					sC.isBot = isBot;
					scheduledCalls.add(sC);
				}else {
					pstmnt = conn.prepareStatement("INSERT INTO conversation (account_id, call_sid, phone_id, direction, timestamp, status, duration) VALUES (?, ?, ?, ?, ?, ?, ?)");
					pstmnt.setInt(1, accId);
					pstmnt.setString(2, sid);
					pstmnt.setString(3, phoneId);
					pstmnt.setInt(4, 1);
					long ts = Long.parseLong(sid);
					pstmnt.setLong(5, ts);
					pstmnt.setString(6, "INITIATED");
					pstmnt.setInt(7, 0);
					pstmnt.execute();
					//SEND CALL NOTIFICATION TO AGENT
					Call1 call = new Call1(sid, phoneId, 1, ts , "INITIATED");
					ongoingCalls.add(new OngoingCall(null, call));
				}
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void sendAiConvoReport(String sId) throws IOException {
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("SELECT report FROM conversation WHERE call_sid = ?");
				pstmnt.setString(1, sId);
				ResultSet rset = pstmnt.executeQuery();
				if(rset.next()) {
					String r = rset.getString(1);
					r = (r == null) ? "Call failed. Conversation not held" : r;
					outgoingRequests.add(new Request1(AI_CONVO_REPORT, new Object[] {sId, r}));
				}
				pstmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void sendStatistics() throws IOException {
			//calculate average debt age
			Map<Integer[], Integer> entries = new HashMap<>();
			entries.put(new Integer[] {0, 6}, 0);
			entries.put(new Integer[] {6, 12}, 0);
			entries.put(new Integer[] {13, 36}, 0);
			entries.put(new Integer[] {37}, 0);
			try {
				Connection conn = getDatabaseConnection();
				Statement stmnt = conn.createStatement();
				ResultSet rset = stmnt.executeQuery("SELECT date_issued FROM loans WHERE amount != paid_amount");
				Long dateIssued, dateAge;
				int e = 0;
				Set<Integer[]> keys = entries.keySet();
				while(rset.next()) {
					dateIssued = rset.getLong(1);
					dateAge = new Date().getTime() - dateIssued;
					dateAge = (dateAge / (24 * 60 * 60 * 1000)) / 30;
					for(Integer[] k : keys) {
						if(k[0] == 37 && dateAge >= k[0]) {
							e = entries.get(k) + 1;
							entries.remove(k);
							entries.put(k, e);
							break;
						}else if(dateAge >= k[0] && dateAge <= k[1]) {
							e = entries.get(k) + 1;
							entries.remove(k);
							entries.put(k, e);
							break;
						}
					}
				}
				int max = 0;
				Integer[] aDA = null; //average debt age
				for(Integer[] k : keys) {
					if(entries.get(k) > max) {
						max = entries.get(k);
						aDA = k;
					}
				}
				String sADA = "";
				if(aDA != null) {
					if(aDA[0] != 37) {
						sADA = aDA[0] + " - " + aDA[1];
					}else {
						sADA = "over 37";
					}
				}
				
				//get the total number of high risk accounts, acc_group C, D
				PreparedStatement pstmnt = conn.prepareStatement("SELECT id FROM accounts WHERE acc_group = ? OR acc_group = ?");
				pstmnt.setString(1, "C");
				pstmnt.setString(2, "D");
				rset = pstmnt.executeQuery();
				int hRA = 0; //number of high risk accounts
				while(rset.next()) {
					hRA++;
				}
				//calculate total debt issued
				rset = stmnt.executeQuery("SELECT amount FROM loans");
				double tDI = 0;
				while(rset.next()) {
					tDI += rset.getDouble(1);
				}
				//calculate total recovered debt
				double rDt = 0;
				rset = stmnt.executeQuery("SELECT paid_amount FROM loans");
				while(rset.next()) {
					rDt += rset.getDouble(1);
				}
				//calculate un-recovered debt
				double uDt = tDI - rDt;
				//determine total accounts, healthy and overdue
				rset = stmnt.executeQuery("SELECT acc_group FROM accounts");
				int c = 0;
				int h = 0;
				int unh = 0;
				while(rset.next()) {
					c++;
					if(rset.getString(1).equals("A")) {
						h++;
					}
				}
				unh = c - h;
				pstmnt.close();
				stmnt.close();
				conn.close();
				//send statistics
				dos.writeInt(RETRIEVE_STATISTICS);
				dos.writeUTF(sADA);
				dos.writeInt(hRA);
				dos.writeDouble(tDI);
				dos.writeDouble(rDt);
				dos.writeDouble(uDt);
				Set<Integer[]> dbTASegK = entries.keySet();
				dos.writeInt(entries.size());
				for(Integer[] i : dbTASegK) {
					if(i[0] == 37) {
						dos.writeUTF("over 37");
					}else {
						dos.writeUTF(i[0] + " - " + i[1]);
					}
					dos.writeInt(entries.get(i));
				}
				dos.writeInt(c);
				dos.writeInt(h);
				dos.writeInt(unh);
			}catch(IOException ex) {
				ex.printStackTrace();
				throw ex;
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void sendAIConvoReport(Object[] ob) throws IOException {
			dos.writeInt(AI_CONVO_REPORT);
			dos.writeUTF((String)ob[0]);
			dos.writeUTF((String)ob[1]);
		}
		
		private void retrieveAISettings() throws IOException {
			try {
				Connection conn = getDatabaseConnection();
				Statement stmnt = conn.createStatement();
				ResultSet rset = stmnt.executeQuery("SELECT sentimental_analysis, personalized_engagement, automated_payment_plans, dispute_resolution FROM ai_settings");
				if(rset.next()) {
					dos.writeInt(RETRIEVE_AI_SETTINGS);
					dos.writeBoolean(rset.getBoolean(1));
					dos.writeBoolean(rset.getBoolean(2));
					dos.writeBoolean(rset.getBoolean(3));
					dos.writeBoolean(rset.getBoolean(4));
				}else {
					//add setting for the first time
					PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO ai_settings (sentimental_analysis, personalized_engagement, automated_payment_plans, dispute_resolution) VALUES (?, ?, ?, ?)");
					pstmnt.setBoolean(1, false);
					pstmnt.setBoolean(2, false);
					pstmnt.setBoolean(3, false);
					pstmnt.setBoolean(4, false);
					pstmnt.execute();
					pstmnt.close();
				}
				stmnt.close();
				conn.close();
			}catch(IOException ex) {
				throw ex;
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void retrieveAccounts() throws IOException {
			try {
				Connection conn = getDatabaseConnection();
				Statement stmnt = conn.createStatement(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE);
				ResultSet rset = stmnt.executeQuery("SELECT id, fullname, acc_group FROM accounts");
				int n = 0;
				while(rset.next()) {
					n++;
				}
				rset.beforeFirst();
				dos.writeInt(RETRIEVE_ACCOUNTS);
				dos.writeInt(n);
				while(rset.next()) {
					dos.writeInt(rset.getInt(1));
					dos.writeUTF(rset.getString(2));
					dos.writeUTF(rset.getString(3));
				}
			}catch(IOException ex) {
				throw ex;
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void updateCallSid(Object[] ob) throws IOException {
			dos.writeUTF((String)ob[0]);
			dos.writeUTF((String)ob[1]);
		}
		
		private void retrieveAccSummary(int id) throws IOException {
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("SELECT fullname, id_number, employment_status, monthly_income, mobile, acc_group, email FROM accounts WHERE id = ?");
				pstmnt.setInt(1, id);
				ResultSet rset = pstmnt.executeQuery();
				rset.next();
				dos.writeInt(RETRIEVE_ACC_SUMMARY);
				dos.writeInt(id);
				dos.writeUTF(rset.getString(1));
				dos.writeUTF(rset.getString(2));
				dos.writeUTF(rset.getString(3));
				dos.writeDouble(rset.getDouble(4));
				dos.writeUTF(rset.getString(5));
				dos.writeUTF(rset.getString(6));
				dos.writeUTF(rset.getString(7));
				pstmnt = conn.prepareStatement("SELECT id, amount, date_issued, date_due, paid_amount FROM loans WHERE account_id = ? AND paid_amount < amount");
				pstmnt.setInt(1, id);
				rset = pstmnt.executeQuery();
				boolean available = false;
				if(rset.next()) {
					available = true;
				}
				dos.writeBoolean(available);
				if(available) {
					dos.writeInt(rset.getInt(1));
					dos.writeDouble(rset.getDouble(2));
					dos.writeLong(rset.getLong(3));
					dos.writeLong(rset.getLong(4));
					dos.writeDouble(rset.getDouble(5));
				}
				pstmnt.close();
				conn.close();
			}catch(IOException ex) {
				ex.printStackTrace();
				throw ex;
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void retrieveCampaigns() throws IOException {
			try {
				Connection conn = getDatabaseConnection();
				Statement stmnt = conn.createStatement(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE);
				ResultSet rset = stmnt.executeQuery("SELECT id, title, model_id, schedule_time FROM campaigns");
				int n = 0;
				while(rset.next()) {
					n++;
				}
				rset.beforeFirst();
				dos.writeInt(RETRIEVE_CAMPAIGNS);
				dos.writeInt(n);
				while(rset.next()) {
					dos.writeInt(rset.getInt(1));
					dos.writeUTF(rset.getString(2));
					dos.writeInt(rset.getInt(3));
					dos.writeLong(rset.getLong(4));
				}
				stmnt.close();
				conn.close();
			}catch(IOException ex) {
				throw ex;
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void updateSettings() throws IOException {
			int setting = dis.readInt();
			boolean state = dis.readBoolean();
			String s = "UPDATE ai_settings SET ";
			switch(setting) {
				case SENTIMENTAL_ANALYSIS : s += "sentimental_analysis = ?"; break;
				case PERSONALIZED_ENGAGEMENT : s += "personalized_engagement = ?"; break;
				case AUTOMATE_PAYMENT_PLANS : s += "automated_payment_plans = ?"; break;
				case DISPUTE_RESOLUTION : s += "dispute_resolution = ?"; break;
			}
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement(s);
				pstmnt.setBoolean(1, state);
				pstmnt.execute();
				pstmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void deleteModel() throws IOException {
			int id = dis.readInt();
			try {
				Connection conn = getDatabaseConnection();
				Statement stmnt = conn.createStatement();
				stmnt.execute("DELETE FROM models WHERE id = " + id);
				stmnt.close();
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void updateMDesignator() throws IOException {
			int n = dis.readInt();
			int i = 0;
			int id;
			int des;
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("UPDATE models SET designation = ? WHERE id = ?");
				while(i++ < n) {
					id = dis.readInt();
					des = dis.readInt();
					pstmnt.setInt(1, des);
					pstmnt.setInt(2, id);
					pstmnt.execute();
				}
				pstmnt.close();
				conn.close();
				outgoingRequests.add(new Request1(UPDATE_MODEL_DESIGNATOR, true));
			}catch(IOException ex) {
				outgoingRequests.add(new Request1(UPDATE_MODEL_DESIGNATOR, false));
				throw ex;
			}catch(Exception ex) {
				outgoingRequests.add(new Request1(UPDATE_MODEL_DESIGNATOR, false));
				ex.printStackTrace();
			}
		}
		
		private void addTemplate() throws IOException {
			int type = dis.readInt();
			String title = dis.readUTF();
			String model = dis.readUTF();
			boolean callReach = dis.readBoolean();
			boolean smsReach = dis.readBoolean();
			boolean emailReach = dis.readBoolean();
			int pastDue = dis.readInt();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO " + ((type == 1) ? " reminders " : " final_notices") + " (title, model_id, call, sms, email, days_past_due) VALUES (?, ?, ?, ?, ?, ?) RETURNING id");
				PreparedStatement pstmnt1 = conn.prepareStatement("SELECT id FROM models WHERE model_name = ?");
				pstmnt1.setString(1, model);
				ResultSet rset = pstmnt1.executeQuery();
				rset.next();
				int mId = rset.getInt(1);
				pstmnt.setString(1, title);
				pstmnt.setInt(2, mId);
				pstmnt.setBoolean(3, callReach);
				pstmnt.setBoolean(4, smsReach);
				pstmnt.setBoolean(5, emailReach);
				pstmnt.setInt(6, pastDue);
				rset = pstmnt.executeQuery();
				rset.next();
				pstmnt1.close();
				pstmnt.close(); 
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void addCampaign() throws IOException {
			String name = dis.readUTF();
			String accGroup = dis.readUTF();
			String model = dis.readUTF();
			boolean callReach = dis.readBoolean();
			boolean smsReach = dis.readBoolean();
			boolean emailReach = dis.readBoolean();
			long timeStamp = dis.readLong();
			try {
				Connection conn = getDatabaseConnection();
				PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO campaigns (title, model_id, account_group, schedule_time, call, sms, email) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id");
				Statement stmnt = conn.createStatement();
				PreparedStatement pstmnt1 = conn.prepareStatement("SELECT id FROM models WHERE model_name = ?");
				pstmnt1.setString(1, model);
				ResultSet rset = pstmnt1.executeQuery();
				rset.next();
				int mId = rset.getInt(1);
				stmnt.close();
				pstmnt.setString(1, name);
				pstmnt.setInt(2, mId);
				pstmnt.setString(3, accGroup);
				pstmnt.setLong(4, timeStamp);
				pstmnt.setBoolean(5, callReach);
				pstmnt.setBoolean(6, smsReach);
				pstmnt.setBoolean(7, emailReach);
				rset = pstmnt.executeQuery();
				rset.next();
				pstmnt.close(); 
				conn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private void uploadModel() throws IOException {
			String mName = dis.readUTF();
			String instructions = dis.readUTF();
			String mood = dis.readUTF();
			String tone = dis.readUTF();
			String attitude = dis.readUTF();
			String audience = dis.readUTF();
			int noOfFiles = dis.readInt();
			int n = 0;
			LinkedList<String[]> scopeFiles = new LinkedList<>();
			String[] s;
			while(n++ < noOfFiles) {
				s = new String[3];
				s[0] = dis.readUTF(); //name
				s[0] = s[0].substring(0, s[0].lastIndexOf("."));
				s[1] = dis.readUTF(); //instruction set
				s[2] = readBytesAndStore("./scopes"); //files
				scopeFiles.add(s);
			}
			//add to database
			try {
				Connection dbConn = getDatabaseConnection();
				PreparedStatement pstmnt = dbConn.prepareStatement("INSERT INTO models (model_name, instructions, mood, tone, attitude, audience) VALUES (?, ?, ?, ?, ?, ?) RETURNING id");
				pstmnt.setString(1, mName);
				pstmnt.setString(2, instructions);
				pstmnt.setString(3, mood);
				pstmnt.setString(4, tone);
				pstmnt.setString(5, attitude);
				pstmnt.setString(6, audience);
				ResultSet rset = pstmnt.executeQuery();
				rset.next();
				final int id = rset.getInt(1);
				pstmnt = dbConn.prepareStatement("INSERT INTO scope_files (model_id, file_name, instructions, file_path) VALUES (?, ?, ?, ?)");
				for(String[] s1 : scopeFiles) {
					pstmnt.setInt(1, id);
					pstmnt.setString(2, s1[0]);
					pstmnt.setString(3, s1[1]);
					pstmnt.setString(4, s1[2]);
					pstmnt.execute();
				}
				pstmnt.close(); 
				dbConn.close();
			}catch(Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private String readBytesAndStore(String parent) throws IOException {
			int size = dis.readInt();
			String name = dis.readUTF();
			String mime = dis.readUTF();
			File file = new File(parent, name + "." + mime);
			int n;
			int count = 0;
			byte[] buffer = new byte[1024];
			try(FileOutputStream fos = new FileOutputStream(file)){
				while(count < size){
		            if((size - count) < buffer.length){
		                n = dis.read(buffer, 0, (size - count));
		            }else {
		                n = dis.read(buffer);  
		            }
		            fos.write(buffer, 0, n);
		            count += n;
		        }
			}
			return file.getAbsolutePath();
		}
		
		private class OutgoingRequestsHandler extends Thread {
			private DataOutputStream dos;
			
			OutgoingRequestsHandler(DataOutputStream dos){
				this.dos = dos;
			}
			
			@Override 
			public void run() {
				Request1 r;
				while(connected) {
					if(!outgoingRequests.isEmpty()) {
						r = outgoingRequests.remove(outgoingRequests.size() - 1);
						try {
							switch(r.command) {
								case CALL_SID : updateCallSid((Object[])r.obj); break;
								case RETRIEVE_ACC_SUMMARY : retrieveAccSummary((int)r.obj); break;
								case RETRIEVE_ONGOING_CALLS : sendOngoingCalls((int)r.obj); break;
								case REFRESH_HISTORY_CONVERSATION : refreshHistoryConversation(); break;
								case RETRIEVE_HISTORY_CONVERSATION : sendHistoryConversations(); break;
								case REFRESH_REPORTS : refreshReports((int)r.obj); break;
								case RETRIEVE_REPORTS : retrieveReports((int)r.obj); break;
								case RETRIEVE_STT : retrieveStt((String)r.obj); break;
								case CALL: sendOngoingCall((Call1)r.obj); break;
								case STT : sendSTT((STT)r.obj); break;
								case REPORT : sendGeneratedReport((Report)r.obj); break;
								case CONVERSATION : sendConversation((Conversation)r.obj); break;
								case CALL_STATUS : updateCallStatus((String[])r.obj); break;
								case RETRIEVE_TRAINED_MODELS : retrieveTrainedModels(); break;
								case TRAIN_MODEL : sendTrainResponse((Object[])r.obj); break;
								case FILTER_CALLS : filterCalls((Object[])r.obj); break;
								case REFRESH_FILTER_CALLS : refreshFilterCalls((Object[])r.obj); break;
								case REFRESH_FILTER_REPORTS : refreshFilterReports((Object[])r.obj); break;
								case FILTER_REPORTS : filterReports((Object[])r.obj); break;
								case REFRESH_SEARCH : refreshSearch((String)r.obj); break;
								case SEARCH : {
									Object[] obj = (Object[])r.obj;
									search((int)obj[0], (String)obj[1]);
								}; break;
								case UPDATE_MODEL_DESIGNATOR : {
									dos.writeInt(UPDATE_MODEL_DESIGNATOR);
									dos.writeBoolean((boolean)r.obj);
								}; break;
								case RETRIEVE_REMINDERS : retrieveTemplates(1); break;
								case RETRIEVE_FINAL_NOTICES : retrieveTemplates(2); break;
								case RETRIEVE_CAMPAIGNS : retrieveCampaigns(); break;
								case RETRIEVE_ACCOUNTS : retrieveAccounts(); break;
								case RETRIEVE_AI_SETTINGS : retrieveAISettings(); break;
								case AI_CONVO_REPORT : sendAIConvoReport((Object[])r.obj); break;
								case RETRIEVE_STATISTICS : sendStatistics(); break;
								case RETRIEVE_PAYMENT_HISTORY : sendPaymentHistory((int)r.obj); break;
								case RETRIEVE_COMMUNICATION : sendCommunicationHistory((int)r.obj); break;
							}
						}catch(IOException ex) {
							ex.printStackTrace();
							connected = false;
						}catch(SQLException ex) {
							ex.printStackTrace();
						}catch(ClassNotFoundException ex) {
							ex.printStackTrace();
						}
					}
				}
			}
			
			private void sendCommunicationHistory(int accountId) throws IOException {
				try {
					Connection conn = getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement("SELECT id, call_sid, direction, timestamp, duration, report FROM conversation WHERE account_id = ?", ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE);
					pstmnt.setInt(1, accountId);
					ResultSet rset = pstmnt.executeQuery();
					int n = 0;
					while(rset.next()) {
						n++;
					}
					dos.writeInt(RETRIEVE_COMMUNICATION);
					dos.writeInt(n);
					dos.writeInt(accountId);
					rset.beforeFirst();
					while(rset.next()) {
						dos.writeInt(rset.getInt(1));
						dos.writeUTF(rset.getString(2));
						dos.writeInt(rset.getInt(3));
						dos.writeLong(rset.getLong(4));
						dos.writeInt(rset.getInt(5));
						dos.writeUTF((rset.getString(6) == null) ? "Call failed. Conversation not held" : rset.getString(6));
					}
					n = 0;
					pstmnt = conn.prepareStatement("SELECT id, type, subject, text, timestamp FROM messages WHERE account_id = ?", ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE);
					pstmnt.setInt(1, accountId);
					rset = pstmnt.executeQuery();
					while(rset.next()) {
						n++;
					}
					dos.writeInt(n);
					System.out.println("n " + n);
					rset.beforeFirst();
					while(rset.next()) {
						dos.writeInt(rset.getInt(1));
						dos.writeInt(rset.getInt(2));
						dos.writeUTF(rset.getString(3));
						dos.writeUTF(rset.getString(4));
						dos.writeLong(rset.getLong(5));
					}
					System.out.println("All comms sent");
					pstmnt.close();
					conn.close();
				}catch(IOException ex) {
					throw ex;
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void sendPaymentHistory(int accountId) throws IOException {
				try {
					Connection conn = getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement("SELECT id, transaction_code, timestamp, amount FROM payments WHERE account_id = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
					pstmnt.setInt(1, accountId);
					ResultSet rset = pstmnt.executeQuery();
					int n = 0;
					while(rset.next()) {
						n++;
					}
					rset.beforeFirst();
					dos.writeInt(RETRIEVE_PAYMENT_HISTORY);
					dos.writeInt(accountId);
					dos.writeInt(n);
					while(rset.next()) {
						dos.writeInt(rset.getInt(1));
						dos.writeUTF(rset.getString(2));
						dos.writeLong(rset.getLong(3));
						dos.writeDouble(rset.getDouble(4));
					}
				}catch(IOException ex) {
					throw ex;
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void sendTrainResponse(Object[] obj) throws IOException {
				dos.writeInt(TRAIN_MODEL);
				dos.writeInt((int)obj[0]);
				dos.writeBoolean((boolean)obj[1]);
			}
			
			private void retrieveTrainedModels() throws IOException {
				try {
					Connection conn = getDatabaseConnection();
					Statement stmnt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
					ResultSet rset = stmnt.executeQuery("SELECT id, model_name, instructions, mood, tone, attitude, audience FROM models");
					PreparedStatement pstmnt = conn.prepareStatement("SELECT file_path FROM scope_files WHERE model_id = ?");
					ResultSet rset1;
					int n = 0;
					while(rset.next()) {
						n++;
					}
					rset.beforeFirst();
					dos.writeInt(RETRIEVE_TRAINED_MODELS);
					dos.writeInt(n);
					int id;
					LinkedList<String> filePaths;
					int i;
					while(rset.next()) {
						id = rset.getInt(1);
						pstmnt.setInt(1, id);
						rset1 = pstmnt.executeQuery();
						filePaths = new LinkedList<>();
						i = 0;
						while(rset1.next()) {
							filePaths.add(rset1.getString(1));
						}
						dos.writeInt(id);
						dos.writeUTF(rset.getString(2));
						dos.writeUTF(rset.getString(3));
						dos.writeUTF(rset.getString(4));
						dos.writeUTF(rset.getString(5));
						dos.writeUTF(rset.getString(6));
						dos.writeUTF(rset.getString(7));
						dos.writeInt(filePaths.size());
						while(i++ < filePaths.size()) {
							dos.writeUTF(filePaths.remove());
						}
					}
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void updateCallStatus(String[] cS) throws IOException {
				dos.writeUTF(cS[0]);
				dos.writeUTF(cS[1]);
			}
			
			private void sendConversation(Conversation conv) throws IOException {
				dos.writeInt(CONVERSATION);
				sendCall(conv);
				dos.writeInt(conv.duration);
			}
			
			private void retrieveStt(String callSid) throws SQLException, IOException, ClassNotFoundException { //retrieve text based conversation
				conversationIdSelected = callSid;
				Connection con = getDatabaseConnection();
				PreparedStatement pstmnt = con.prepareStatement("SELECT id, call_sid, sender, text, timestamp FROM stt WHERE call_sid = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
				pstmnt.setString(1, callSid);
				ResultSet rset = pstmnt.executeQuery();
				int i = 0;
				while(rset.next()) {
					i++;
				}
				rset.beforeFirst();
				dos.writeInt(RETRIEVE_STT);
				dos.writeInt(i);
				while(rset.next()) {
					dos.writeInt(rset.getInt(1));
					dos.writeUTF(rset.getString(2));
					dos.writeInt(rset.getInt(3));
					dos.writeUTF(rset.getString(4));
					dos.writeLong(rset.getLong(5));
				}
				rset.close();
				pstmnt.close();
				con.close();
			}
			
			private void refreshReports(int category) throws SQLException, IOException, ClassNotFoundException {
				currentReportCursor = 0;
				retrieveReports(category);
			}
			
			private void retrieveReports(int category) throws SQLException, IOException, ClassNotFoundException { //retrieve reports from db and send to the user
				long[] time = calculateReportTime(category);
				long startTime = time[0];
				long endTime = time[1];
				Connection con = getDatabaseConnection();
				PreparedStatement pstmnt = con.prepareStatement("SELECT id, call_sid, brief, details, timestamp FROM reports WHERE timestamp >= ? AND timestamp <= ? AND id > ?");
				pstmnt.setLong(1, startTime);
				pstmnt.setLong(2, endTime);
				pstmnt.setInt(3, currentReportCursor);
				ResultSet rset = pstmnt.executeQuery();
				int i = 0;
				LinkedList<Report> list = new LinkedList<>();
				while(rset.next() && i++ <= 10) {
					list.add(new Report(category, rset.getInt(1), rset.getString(2), rset.getString(3), rset.getString(4), rset.getLong(5)));
				}
				if(list.size() > 0) {
					currentReportCursor = list.get(list.size() - 1).id;
				}
				Report rP;
				dos.writeInt(RETRIEVE_REPORTS);
				dos.writeInt(i); 
				while(!list.isEmpty()) {
					rP = list.poll();
					dos.writeInt(rP.category);
					dos.writeInt(rP.id);
					dos.writeUTF(rP.call_sid);
					dos.writeUTF(rP.brief);
					dos.writeUTF(rP.details);
					dos.writeLong(rP.timeStamp);
				}
				rset.close();
				pstmnt.close();
				con.close();
			}
			
			private long[] calculateReportTime(int category) {
				switch(category) {
					case 1 : {
						Date d = new Date();
						d.setHours(0);
						d.setMinutes(0);
						d.setSeconds(0);
						long startTime = d.getTime();
						d = new Date();
						d.setHours(23);
						d.setMinutes(59);
						d.setSeconds(59);
						long endTime = d.getTime();
						return new long[] {startTime, endTime};
					} 
					
					case 2 : {
						Date d = new Date();
						d.setHours(0);
						d.setMinutes(0);
						d.setSeconds(0);
						int daysPast = d.getDay() - 1;
						long offsetMills = 24 * daysPast * 60 * 60 * 1000;
						long startTime = d.getTime() - offsetMills;
						int daysTo = 6 - d.getDay();
						long toMills = 24 * daysTo * 60 * 60 * 1000;
						long endTime = d.getTime() + toMills;
						d = new Date(endTime);
						d.setHours(23);
						d.setMinutes(59);
						d.setSeconds(59);
						System.out.println("End time " + d.toString());
						return new long[] {startTime, d.getTime()};
					}
					
					case 3 : {
						Date d = new Date();
						d.setHours(0);
						d.setMinutes(0);
						d.setSeconds(0);
						int daysPast = d.getDate() - 1;
						long offsetMills = 24 * daysPast * 60 * 60 * 1000;
						long startTime = d.getTime() - offsetMills;
						int month = d.getMonth();
						int days;
						if(month == 0 || month == 2 || month == 4 || month == 6 || month == 7 || month == 9 || month == 11) {
							days = 31;
						}else if(month == 1) {
							 if((d.getYear() + 1900) % 4 == 0) {
								 days = 29;
							 }else {
								 days = 28;
							 }
						}else {
							days = 30;
						}
						d.setDate(days);
						d.setHours(23);
						d.setMinutes(59);
						d.setSeconds(59);
						System.out.println("End time " + d.toString());
						return new long[] {startTime, d.getTime()};
					}
					
					default: return null;
				}
			}
			
			private void refreshHistoryConversation() throws SQLException, IOException, ClassNotFoundException {
				currentHistoryCursor = 0;
				sendHistoryConversations();
			}
			
			private void refreshFilterCalls(Object[] obj) throws IOException {
				currentFilterCallsCursor = 0;
				filterCalls(obj);
			}
			
			private void refreshFilterReports(Object[] obj) throws IOException {
				currentFilterReportsCursor = 0;
				filterReports(obj);
			}
			
			private void refreshSearch(String s) throws IOException {
				currentSearchReportsCursor = 0;
				currentSearchCallsCursor = 0;
				search(1, s);
				search(2, s);
			}
			
			private void search(int category, String s) throws IOException {
				String q1 = "SELECT id, call_sid, phone_id, direction, timestamp, status, duration FROM conversation WHERE phone_id = ? AND id > ?";
				String q2 = "SELECT reports.id, reports.call_sid, reports.brief, reports.details, reports.timestamp FROM reports INNER JOIN conversation ON conversation.call_sid = reports.call_sid WHERE conversation.phone_id = ? AND reports.id = ?";
				try {
					Connection conn = getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement((category == 1) ? q1 : q2);
					pstmnt.setString(1, s);
					pstmnt.setInt(2, (category == 1) ? currentSearchCallsCursor : currentSearchReportsCursor);
					ResultSet rset = pstmnt.executeQuery();
					int i = 0;
					LinkedList<Object> list = new LinkedList<>();
					while(rset.next() && i++ <= 10) {
						if(category == 1) {
							list.add(new Conversation(rset.getInt(1), rset.getString(2), rset.getString(3), rset.getInt(4), rset.getLong(5), rset.getString(6), rset.getInt(7)));
						}else {
							list.add(new Report(4, rset.getInt(1), rset.getString(2), rset.getString(3), rset.getString(4), rset.getLong(5)));
						}
					}
					if(list.size() > 0) {
						if(category == 1) {
							currentSearchCallsCursor = ((Conversation)list.get(list.size() - 1)).id;
						}else {
							currentSearchReportsCursor = ((Report)list.get(list.size() - 1)).id;
						}
					}
					dos.writeInt(SEARCH);
					dos.writeInt(category);
					dos.writeUTF(s);
					dos.writeInt(i);
					Conversation conv;
					while(!list.isEmpty()) {
						if(category == 1) {
							sendCall(conv = (Conversation)list.poll());
							dos.writeInt(conv.duration);
						}else {
							sendReport((Report)list.poll());
						}
					}
					rset.close();
					pstmnt.close();
					conn.close();
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void filterReports(Object[] obj) throws IOException {
				String q1 = "SELECT reports.id, reports.call_sid, reports.brief, reports.details, reports.timestamp, conversation.direction FROM reports INNER JOIN conversation ON reports.call_sid = conversation.call_sid WHERE reports.timestamp >= ? AND reports.timestamp <= ? AND reports.id > ?";
				String q2 = "SELECT reports.id, reports.call_sid, reports.brief, reports.details, reports.timestamp, conversation.direction FROM reports INNER JOIN conversation ON reports.call_sid = conversation.call_sid WHERE reports.timestamp >= ? AND reports.timestamp <= ? AND reports.id > ? AND conversation.direction = ?";
				int filterType = (int)obj[2];
				int direction = (filterType == 3) ? 1 : 2;
				try {
					Connection con = getDatabaseConnection();
					PreparedStatement pstmnt = con.prepareStatement((filterType == 1) ? q1 : q2);
					pstmnt.setLong(1, (long)obj[0]);
					pstmnt.setLong(2, (long)obj[1]);
					pstmnt.setInt(3, currentFilterReportsCursor);
					if(filterType != 1) {
						pstmnt.setInt(4, direction);
					}
					ResultSet rset = pstmnt.executeQuery();
					int i = 0;
					LinkedList<Report> list = new LinkedList<>();
					Report rep;
					while(rset.next() && i++ <= 10) {
						list.add(rep = new Report(4, rset.getInt(1), rset.getString(2), rset.getString(3), rset.getString(4), rset.getLong(5)));
						rep.setDirection(rset.getInt(6));
					}
					if(list.size() > 0) {
						currentFilterReportsCursor = list.get(list.size() - 1).id;
					}
					dos.writeInt(FILTER_REPORTS);
					dos.writeInt(i);
					while(!list.isEmpty()) {
						rep = list.poll();
						sendReport(rep);
					}
					rset.close();
					pstmnt.close();
					con.close();
				}catch(IOException ex) {
					throw ex;
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void sendReport(Report rep) throws IOException {
				dos.writeInt(rep.category);
				dos.writeInt(rep.id);
				dos.writeUTF(rep.call_sid);
				dos.writeUTF(rep.brief);
				dos.writeUTF(rep.details);
				dos.writeLong(rep.timeStamp);
				dos.writeInt(rep.direction);
			}
			
			private void filterCalls(Object[] obj) throws IOException {
				String q1 = "SELECT id, call_sid, phone_id, direction, timestamp, status, duration FROM conversation WHERE timestamp >= ? AND timestamp <= ? AND id > ?";
				String q2 = "SELECT id, call_sid, phone_id, direction, timestamp, status, duration FROM conversation WHERE timestamp >= ? AND timestamp <= ? AND id > ? AND direction = ?";
				int filterType = (int)obj[2];
				int direction = (filterType == 3) ? 1 : 2;
				try {
					Connection con = getDatabaseConnection();
					PreparedStatement pstmnt = con.prepareStatement((filterType == 1) ? q1 : q2);
					pstmnt.setLong(1, (long)obj[0]);
					pstmnt.setLong(2, (long)obj[1]);
					pstmnt.setInt(3, currentFilterCallsCursor);
					if(filterType != 1) {
						pstmnt.setInt(4, direction);
					}
					ResultSet rset = pstmnt.executeQuery();
					int i = 0;
					LinkedList<Conversation> list = new LinkedList<>();
					while(rset.next() && i++ <= 10) {
						list.add(new Conversation(rset.getInt(1), rset.getString(2), rset.getString(3), rset.getInt(4), rset.getLong(5), rset.getString(6), rset.getInt(7)));
					}
					if(list.size() > 0) {
						currentFilterCallsCursor = list.get(list.size() - 1).id;
					}
					Conversation conv;
					dos.writeInt(FILTER_CALLS);
					dos.writeInt(i);
					while(!list.isEmpty()) {
						conv = list.poll();
						sendCall(conv);
						dos.writeInt(conv.duration);
					}
					rset.close();
					pstmnt.close();
					con.close();
				}catch(IOException ex) {
					throw ex;
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void retrieveTemplates(int type) throws IOException {
				try {
					Connection conn = getDatabaseConnection();
					Statement stmnt = conn.createStatement(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE);
					ResultSet rset = stmnt.executeQuery("SELECT id, title, model_id, call, sms, days_past_due FROM " + ((type == 1) ? "reminders" : "final_notices"));
					int n = 0;
					while(rset.next()) {
						n++;
					}
					rset.beforeFirst();
					dos.writeInt((type == 1) ? RETRIEVE_REMINDERS : RETRIEVE_FINAL_NOTICES);
					dos.writeInt(n); 
					while(rset.next()) {
						dos.writeInt(rset.getInt(1));
						dos.writeUTF(rset.getString(2));
						dos.writeInt(rset.getInt(3));
						dos.writeBoolean(rset.getBoolean(4));
						dos.writeBoolean(rset.getBoolean(5));
						dos.writeInt(rset.getInt(6));
					}
					stmnt.close();
					conn.close();
				}catch(IOException ex) {
					throw ex;
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void sendHistoryConversations() throws SQLException, IOException, ClassNotFoundException {
				Connection con = getDatabaseConnection();
				PreparedStatement pstmnt = con.prepareStatement("SELECT id, call_sid, phone_id, direction, timestamp, status, duration FROM conversation WHERE id > ? AND (status = 'NO_ANSWER' OR status = 'FAILED' OR status = 'COMPLETED' OR status = 'BUSY')");
				pstmnt.setInt(1, currentHistoryCursor);
				ResultSet rset = pstmnt.executeQuery();
				int i = 0;
				LinkedList<Conversation> list = new LinkedList<>();
				while(rset.next() && i < 20) {
					list.add(new Conversation(rset.getInt(1), rset.getString(2), rset.getString(3), rset.getInt(4), rset.getLong(5), rset.getString(6), rset.getInt(7)));
					i++;
				}
				if(list.size() > 0) {
					currentHistoryCursor = list.get(list.size() - 1).id;
				}
				Conversation conv;
				dos.writeInt(RETRIEVE_HISTORY_CONVERSATION);
				dos.writeInt(i);
				while(!list.isEmpty()) {
					conv = list.poll();
					sendCall(conv);
					dos.writeInt(conv.duration);
				}
				rset.close();
				pstmnt.close();
				con.close();
			}
			
			private void sendGeneratedReport(Report r) throws IOException {
				dos.writeInt(REPORT);
				dos.writeInt(r.id);
				dos.writeUTF(r.call_sid);
				dos.writeUTF(r.details);
				dos.writeLong(r.timeStamp);
			}
			
			private void sendSTT(STT stt) throws IOException {
				dos.writeInt(STT);
				dos.writeInt(stt.id);
				dos.writeUTF(stt.conversationId);
				dos.writeInt(stt.sender);
				dos.writeUTF(stt.text);
				dos.writeLong(stt.timestamp);
			}
			
			private void sendOngoingCalls(int direction) throws IOException {
				String s = "";
				if(direction != 1) {
					s = " AND direction = ?";
				}
				try {
					String s1 = "SELECT call_sid, phone_id, direction, timestamp, status FROM conversation WHERE status = ? or status = ? or status = ?";
					if(direction != 1) {
						s1 += s;
					}
					Connection conn = Server.getDatabaseConnection();
					PreparedStatement pstmnt = conn.prepareStatement(s1, ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE);
					pstmnt.setString(1, Call.Status.IN_PROGRESS.toString());
					pstmnt.setString(2, Call.Status.RINGING.toString());
					pstmnt.setString(3, Call.Status.QUEUED.toString());
					if(direction != 1) {
						pstmnt.setInt(4, (direction == 2) ? 2 : 1);
					}
					ResultSet rset = pstmnt.executeQuery();
					int count = 0;
					while(rset.next()) {
						count++;
					}
					rset.beforeFirst();
					dos.writeInt(RETRIEVE_ONGOING_CALLS);
					dos.writeInt(count);
					while(rset.next()) {
						dos.writeUTF(rset.getString(1));
						dos.writeUTF(rset.getString(2));
						dos.writeInt(rset.getInt(3));
						dos.writeLong(rset.getLong(4));
						dos.writeUTF(rset.getString(5));
					}
					pstmnt.close();
					conn.close();
					System.out.println("Ongoing calls forwarded");
				}catch(IOException ex) {
					ex.printStackTrace();
					throw ex;
				}catch(Exception ex) {
					ex.printStackTrace();
				}
			}
			
			private void sendCall(Call1 c) throws IOException {
				dos.writeUTF(c.call_sid);
				dos.writeUTF(c.phoneId);
				dos.writeInt(c.direction);
				dos.writeLong(c.timeStamp);
				dos.writeUTF(c.status);
			}
			
			private void sendOngoingCall(Call1 c) throws IOException {
				dos.writeInt(CALL);
				sendCall(c);
			}
		}
	}
	
	
	private static class OngoingCall {
		ScheduledCall sC;
		Call1 c1;
		
		OngoingCall(ScheduledCall sC, Call1 c1){
			this.sC = sC;
			this.c1 = c1;
		}
	}
	
	private static class ScheduleComm {
		int type; //1 campaign, 2 Reminders, 3 Final Notices
		Account acc;
		Loan loan;
		int modelId;
		
		ScheduleComm(int type, Account acc, Loan l, int modelId){
			this.type = type;
			this.acc = acc; this.loan = l; this.modelId = modelId;
		}
	}
	
	private static class ScheduledCall extends ScheduleComm {
		int accId;
		String sid;
		boolean isBot = false;
		
		ScheduledCall(int type, Account acc, Loan l, int modelId) {
			super(type, acc, l, modelId);
		}
	}
	
	private static class ScheduledEmail extends ScheduleComm {
		
		ScheduledEmail(int type, Account acc, Loan l, int modelId){
			super(type, acc, l, modelId);
		}
	}
	
	private static class ScheduledSms extends ScheduleComm {
		
		ScheduledSms(int type, Account acc, Loan l, int modelId){
			super(type, acc, l, modelId);
		}
	}
	
	private static class Email {
		String txt, subject, mobile;
		
		Email(String txt, String sbj, String mobile){
			this.txt = txt;
			this.subject = sbj;
			this.mobile = mobile;
		}
	}
	
	private static class Sms {
		String txt, mobile;
		
		Sms(String txt, String mobile){
			this.txt = txt; this.mobile = mobile;
		}
	}
	
	
	private static class Account {
		int id;
		String name, mobile;
		
		Account(int id, String fName, String mobile){
			this.id = id;
			this.name = fName;
			this.mobile = mobile;
		}
	}
	
	private static class Loan {
		int accountId;
		double amount, paidAmount;
		long dateDue, dateIssued;
		
		Loan(int accountId, double amount, long dateDue, long dateIssued, double paidAmount){
			this.accountId = accountId; this.amount = amount; this.dateIssued = dateIssued; this.dateDue = dateDue;
			this.paidAmount = paidAmount;
		}
	}
	
	private static class Campaign {
		int id, modelId;
		boolean sms, call, email;
		String title, accGroup;
		
		Campaign(int id, String title, int modelId, String accGroup, boolean call, boolean sms, boolean email){
			this.id = id; this.title = title; this.modelId = modelId; this.accGroup = accGroup; this.call = call; this.sms = sms; this.email = email;
		}
		
	}
	
	private static class FinalNotice {
		int id, modelId, daysPastDue;
		boolean sms, call, email;
		String title, accGroup;
		
		FinalNotice(int id, String title, int modelId, String accGroup, boolean call, boolean sms, boolean email, int daysPastDue){
			this.id = id; this.title = title; this.modelId = modelId; this.accGroup = accGroup; this.call = call; this.sms = sms; this.email = email; this.daysPastDue = daysPastDue;
		}
	}
	
	private static class Reminder {
		int id, modelId, daysPastDue;
		boolean sms, call, email;
		String title, accGroup;
		
		Reminder(int id, String title, int modelId, String accGroup, boolean call, boolean sms, boolean email, int daysPastDue){
			this.id = id; this.title = title; this.modelId = modelId; this.accGroup = accGroup; this.call = call; this.sms = sms; this.email = email; this.daysPastDue = daysPastDue;
		}
	}
	
	private static class Call1 {
		String call_sid;
		final String phoneId;
		final int direction;
		long timeStamp;
		String status;
		
		Call1(String call_sid, String phoneId, int direction, long timeStamp, String status){
			this.call_sid = call_sid;
			this.phoneId = phoneId;
			this.direction = direction;
			this.timeStamp = timeStamp;
			this.status = status;
		}
	}
	
	private static class Conversation extends Call1 {
		final int id;
		final int duration;
		
		Conversation(int id, String call_sid, String phoneId, int direction, long timeStamp, String status, int duration){
			super(call_sid, phoneId, direction, timeStamp, status);
			this.duration = duration;
			this.id = id;
		}
	}
	
	private static class Request1 {
		int command;
		Object obj;
		
		Request1(int c, Object o){
			this.command = c;
			this.obj = o;
		}
	}
	
	private static class STT {
		final int id;
		final String conversationId;
		final String text;
		final int sender;
		final long timestamp;
		
		STT(int id, String conversationId, int sender, String txt, long timeStamp){
			this.id = id;
			this.conversationId = conversationId;
			this.text = txt;
			this.sender = sender;
			this.timestamp = timeStamp;
		}
	}
	
	private static class Report {
		final int category;
		final int id;
		final String call_sid;
		final String brief;
		final String details;
		int direction;
		final long timeStamp;
		
		Report(int category, int id, String call_sid, String brief, String details, long timeStamp){
			this.category = category;
			this.id = id;
			this.call_sid = call_sid;
			this.brief = brief;
			this.details = details;
			this.timeStamp = timeStamp;
			CustomQueue<String> cs = new CustomQueue<>();
		}
		
		void setDirection(int direction) {
			this.direction = direction;
		}
		
		
	}
	
	private static class CustomQueue<R> extends LinkedList<R> {
		private ReentrantLock lock = new ReentrantLock();
		private Condition newItem = lock.newCondition();
		
		
		public void addToFirst(R r) {
			lock.lock();
			addFirst(r);
			if(size() == 1) {
				newItem.signal();
			}
			lock.unlock();
		}
		
		@Override
		public boolean offer(R r) {
			lock.lock();
			add(r);
			if(size() == 1) {
				newItem.signal();
			}
			lock.unlock();
			return true;
		}
		
		@Override
		public R poll() {
			lock.lock();
			R r = null;
			try {
				while(isEmpty()) {
					newItem.await();
				}
				r = removeLast();
			}catch(InterruptedException x) {
				x.printStackTrace();
			}
			lock.unlock();
			return r;
		}
	}
	
	
	private static class IVRServer {
		
		static void runHttpServer() {
			try {
				int port = 8011;
				HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
				server.createContext("/hello", new HelloHandler());
				HttpContext context2 = server.createContext("/conversation", new OngoingConversationHandler());
				HttpContext context3 = server.createContext("/status", new StatusChangeHandler());
				HttpContext context4 = server.createContext("/endConversation", new EndConversationHandler());
				context2.getFilters().add(new ParameterFilter());
				context3.getFilters().add(new ParameterFilter());
				context4.getFilters().add(new ParameterFilter());
				server.setExecutor(Executors.newCachedThreadPool());

	            //start the server
	            server.start();
		           
	            System.out.println("Https Server started");
			}catch(Exception e) {
				
			}
		}
		
		private static class StatusChangeHandler implements HttpHandler {
			
			@Override
			public void handle(HttpExchange he) {
				Map<String, Object> params = (Map<String, Object>)he.getAttribute("parameters");
	    	  	final String callSid = (String)params.get("CallSid");
	    	  	System.out.println("Call status changed. Call sid " + callSid);
	    	  	final Call call1 = Call.fetcher(callSid).fetch();
	    	  	
	    	  	//update call status in the database then send UI notification
	    	  	try {
	    	  		Connection conn = getDatabaseConnection();
	    	  		PreparedStatement pstmnt = conn.prepareStatement("UPDATE conversation SET status = ? WHERE call_sid = ?");
	    	  		pstmnt.setString(1, callSid);
	    	  		pstmnt.setString(2, call1.getStatus().toString().toUpperCase());
	    	  		pstmnt.execute();
	    	  		
	    	  		//notify UI
  					SocketHandler.outgoingRequests.add(new Request1(CALL_STATUS, new Object[] {callSid, Call.Status.COMPLETED.toString()}));
	    	  		
  					if(call1.getStatus() == Call.Status.COMPLETED || call1.getStatus() == Call.Status.CANCELED || call1.getStatus() == Call.Status.FAILED || call1.getStatus() == Call.Status.NO_ANSWER || call1.getStatus() == Call.Status.BUSY) {
	    	  			Iterator<OngoingCall> it  = ongoingCalls.iterator();
	    	  			while(it.hasNext()) {
	    	  				if(it.next().c1.call_sid.equals(callSid)) {
	    	  					it.remove();
	    	  					chatService.ask("Generate a report of conversation_id : " + callSid, callSid, he, true, false, null);
	    	  					break;
	    	  				}
	    	  			}
	    	  		}
	    	  		
	    	  	}catch(Exception ex) {
	    	  		ex.printStackTrace();
	    	  	}
	    	}
		}
		
		private static class HelloHandler implements HttpHandler {
			  
		      @Override
		      public void handle(HttpExchange he){
		    	  	try {
		    	  		//Retrieve call information
			    	    Map<String, Object> params = (Map<String, Object>)he.getAttribute("parameters");
			    	    Set<String> keys = params.keySet();
			    	    for(String k : keys) {
			    	    	System.out.println(k + " : " + params.get(k).toString());
			    	    }
			    	  	String callSid = (String)params.get("CallSid");
			    	  	System.out.println("Call received. Call sid " + callSid);
			    	  	Call call = Call.fetcher(callSid).fetch();
			    	  	boolean available = false;
			    	  	OngoingCall o1 = null;
			    	  	for(OngoingCall o : ongoingCalls) {
			    	  		if(o.c1.call_sid.equals(callSid)) {
			    	  			o1 = o;
			    	  			available = true;
			    	  			break;
			    	  		}
			    	  	}
			    	  	if(available) { //this is an outgoing call
			    	  		o1.c1.status = call.getStatus().toString();
		    	  			
		    	  			//update call status in db
		    	  			Connection conn = getDatabaseConnection();
			    	  		PreparedStatement pstmnt = conn.prepareStatement("UPDATE conversation SET status = ? WHERE call_sid = ?");
			    	  		pstmnt.setString(1, callSid);
			    	  		pstmnt.setString(2, call.getStatus().toString().toUpperCase());
			    	  		pstmnt.execute();
			    	  		
			    	  		//update call status in UI
		    	  			SocketHandler.outgoingRequests.add(new Request1(CALL_STATUS, new Object[] {call.getSid(), call.getStatus().toString()}));
			    	  		
		    	  			//notify AI of initialization of conversation
				        	String prompt = "NEW CONVERSATION /n conversation_id : " + callSid + "/n model_id: " + o1.sC.modelId + "/n user_profile: name - " + o1.sC.acc.name + "loan amount - " + o1.sC.loan.amount + 
				        			" date_issued - " + new Date(o1.sC.loan.dateIssued).toString() + " due_date - " + new Date(o1.sC.loan.dateDue).toString();
				        	
				        	chatService.ask(prompt, callSid, he, false, true, null);				        
			    	  	}
		    	  	}catch(Exception ex) {
		    	  		ex.printStackTrace();
		    	  	}
		    	  	
		      }
		      
		}
		
		private static class EndConversationHandler implements HttpHandler {
			
			@Override 
			public void handle(HttpExchange he) {
				String s[] = he.getRequestURI().getPath().split("/");
			 	String callSid = s[4];
	        	Call c = Call.updater(callSid).setStatus(Call.UpdateStatus.COMPLETED).update();
	        	//update call status and duration in db
	        	try {
	        		Connection dbConn = getDatabaseConnection();
	        		PreparedStatement pstmnt = dbConn.prepareStatement("UPDATE conversation SET status = ?, duration = ? WHERE id = ?");
	        		pstmnt.setString(1, c.getStatus().toString().toUpperCase());
	        		pstmnt.setInt(2, Integer.parseInt(c.getDuration()));
	        		pstmnt.setString(3, callSid);
	        		pstmnt.execute();
	        		pstmnt.close();
	        		dbConn.close();
	        	}catch(Exception ex) {
	        		ex.printStackTrace();
	        	}
	        	//notify UI
        		SocketHandler.outgoingRequests.add(new Request1(CALL_STATUS, new Object[] {callSid, Call.Status.COMPLETED.toString()}));
             	//generate an AI overview of this conversation
 	  			String instructions = "Generate a brief report of the conversation with conversation_id : " + callSid;		
 	  			chatService.ask(instructions, callSid, he, true, false, null);
			}
		}
		
		private static  class OngoingConversationHandler implements HttpHandler {
			
			 @Override
		      public void handle(HttpExchange he){ 
				    String s[] = he.getRequestURI().getPath().split("/");
				 	String callSid = s[4];
				 	System.out.println("Call sid : " + callSid);
		    	  	Map<String, Object> params = (Map<String, Object>)he.getAttribute("parameters");
		    	  	String speechResult = (String)params.get("SpeechResult");
		    	  	try {
		    	  		Connection conn = getDatabaseConnection();
			        	PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO stt (call_sid, sender, timestamp, text) VALUES (?, ?, ?, ?) RETURNING id");
			        	pstmnt.setString(1, callSid);
			        	pstmnt.setInt(2, 2); //1 for machine 2 for human
			        	pstmnt.setString(3, new Date().toString());
			        	pstmnt.setString(4, speechResult);
			        	ResultSet rset = pstmnt.executeQuery();
			        	rset.next();
			        	int sttId = rset.getInt(1);
			        	pstmnt.close();
			        	conn.close();
			        	//forward STT to UI
			        	if(conversationIdSelected.equals(callSid)) {
			        		STT stt = new STT(sttId, callSid, 2, speechResult, new Date().getTime());
				        	SocketHandler.outgoingRequests.add(new Request1(STT, stt));
			        	}
		    	  	}catch(Exception ex) {
		    	  		ex.printStackTrace();
		    	  	}
		    	  	//send conversation to AI
		    	  	String prompt = "conversation_id : " + callSid + "query : " + speechResult; 
		    	  	chatService.ask(prompt, callSid, he, false, false, null);
		      }
		}
		
		
		private static class ParameterFilter extends Filter {

			@Override
			public String description() {
				return "Parse the requested URI for parameters";
			}

			@Override
			public synchronized void doFilter(HttpExchange exchange, Chain chain) throws IOException {
				parseGetParameters(exchange);
				parsePostParameters(exchange);
				chain.doFilter(exchange);
			}
			
			private void parseGetParameters(HttpExchange exchange) throws UnsupportedEncodingException {
				Map<String, Object> parameters = new HashMap<String, Object>();
				URI requestedUri = exchange.getRequestURI();
				String query = requestedUri.getRawQuery();
				parseQuery(query, parameters);
			}
			
			private void parseQuery(String query, Map<String, Object> parameters) throws UnsupportedEncodingException {
				if(query != null) {
					String pairs[] = query.split("[&]");
					for(String pair : pairs) {
						String param[] = pair.split("[=]");
						String key = null;
						String value = null;
						if(param.length > 0) {
							key = URLDecoder.decode(param[0], System.getProperty("file.encoding"));
						}
						if(param.length > 1) {
							value = URLDecoder.decode(param[1], System.getProperty("file.encoding"));
						}
						if(parameters.containsKey(key)) {
							Object obj = parameters.get(key);
							if(obj instanceof List<?>) {
								List<String> values = (List<String>)obj;
								values.add(value);
							}else if(obj instanceof String) {
								List<String> values = new ArrayList<String>();
								values.add((String)obj);
								values.add(value);
								parameters.put(key, values);
							}
						} else {
							parameters.put(key, value);
						}
					}
				}
			}
			
			private void parsePostParameters(HttpExchange exchange) throws IOException {
				if("post".equalsIgnoreCase(exchange.getRequestMethod())) {
					Map<String, Object> parameters = (Map<String, Object>)exchange.getAttribute("parameters");
					InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "utf-8");
					BufferedReader br = new BufferedReader(isr);
					String query = br.readLine();
					parseQuery(query, parameters);
				}
			}
			
		}
	}
	
}