package com.circonus.fq2scribe;

import org.apache.commons.codec.binary.Base64;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TTransportException;
import scribe.LogEntry;
import scribe.scribe.Client;

import com.omniti.labs.FqClient;
import com.omniti.labs.FqClientImplNoop;
import com.omniti.labs.FqCommand;
import com.omniti.labs.FqMessage;

import org.apache.commons.cli.*;

import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.Socket;
import java.io.IOException;

public class Fq2Scribe {
    protected final static String DEFAULT_PROGRAM =
        "prefix:\"scribe.zipkin.\"";
    private boolean connected = false;
    private String host;
    private int port;
    private Client client;
    private TFramedTransport transport;
    private List<LogEntry> logEntries;

    public void connect() {
        if(connected) return;
        try {
            TSocket sock = new TSocket(new Socket(host, port));
            transport = new TFramedTransport(sock);
            TBinaryProtocol protocol = new TBinaryProtocol(transport, false, false);
            client = new Client(protocol, protocol);
            connected = true;
        } catch (TTransportException e) {
            transport.close();
            connected = false;
            System.err.println(e);
        } catch (UnknownHostException e) {
            connected = false;
            System.err.println(e);
        } catch (IOException e) {
            connected = false;
            System.err.println(e);
        } catch (Exception e) {
            System.err.println(e);
        }
    }
    public void send(String category, String message) {
        LogEntry entry = new LogEntry(category, message);

        logEntries.add(entry);
        connect();
        try { client.Log(logEntries); }
        catch (TTransportException e) {
            transport.close();
            connected = false;
        }
        catch (Exception e) {
            System.err.println(e);
        }
        finally { logEntries.clear(); }
    }

    public Fq2Scribe(String _host, int _port) {
        logEntries = new ArrayList<LogEntry>(1);
        host = _host;
        port = _port;
    }

    protected static class FqConnector extends FqClientImplNoop {
        private Fq2Scribe s;
        private String exchange;
        private String prog;
        private Map<String,Long> last_status;
        public FqConnector(Fq2Scribe _s, String _exchange, String _prog) {
            s = _s; exchange = _exchange; prog = _prog;
        }
        public void dispatchAuth(FqCommand.Auth a) {
            if(a.success()) {
	client.setHeartbeat(500);
	FqCommand.BindRequest breq =
	    new FqCommand.BindRequest(exchange, prog, false);
	client.send(breq);
            }
        }
        public void dispatchStatusRequest(FqCommand.StatusRequest cmd) {
            Date d = cmd.getDate();
            Map<String,Long> m = cmd.getMap();
            boolean has_keys = false;
            for(String key : m.keySet()) {
	if(last_status == null || !m.get(key).equals(last_status.get(key)))
	    System.err.println("    " + key + " : " + ((last_status == null) ? 0 : last_status.get(key)) + " -> " +  m.get(key));
	has_keys = true;
            }
            if(has_keys) last_status = m;
        }

        public void dispatch(FqMessage m) {
            try {
	s.send("zipkin", Base64.encodeBase64String(m.getPayload()));
            } catch(Exception e) { System.err.println(e); }
        }
    }

    public static void main(String []args) {
        String scribehost = null;
        Integer scribeport = null;
        String fq_exchange = null;
        String fq_source = null;
        String fq_pass = null;
        String fq_prog = null;
        String []fq_hosts = null;

        Options options = new Options();
        options.addOption(new Option("h", "print this message"));
        options.addOption(new Option("s", "Fq source (user/queue)"));
        options.addOption(new Option("p", "Fq password"));
        options.addOption(new Option("prog", "Fq program"));
        options.addOption(new Option("e", "Fq exchange"));
        options.addOption(OptionBuilder.withArgName("fq")
                          .hasArgs()
                          .withDescription("fq hosts")
                          .create("fq"));
        options.addOption(OptionBuilder.withArgName("scribehost")
                          .hasArg()
                          .withDescription("host to scribe to")
                          .create("scribehost"));
        options.addOption(OptionBuilder.withArgName("scribeport")
                          .hasArg()
                          .withDescription("port to scribe to")
                          .create("scribeport"));

        CommandLineParser parser = new PosixParser();
        try {
            CommandLine line = parser.parse( options, args );
            if(line.hasOption("h")) {
	HelpFormatter formatter = new HelpFormatter();
	formatter.printHelp( "fq2scribe", options );
	System.exit(0);
            }
            scribehost = line.getOptionValue("scribehost", "127.0.0.1");
            scribeport = Integer.parseInt(line.getOptionValue("scribeport", "1490"));
            fq_hosts = line.getOptionValues("fq");
            if(fq_hosts == null) {
	System.err.println("-fq hosts required");
	System.exit(2);
            }
            fq_exchange = line.getOptionValue("e", "logging");
            fq_source = line.getOptionValue("s", "fq2scribe");
            fq_pass = line.getOptionValue("p", "password");
            fq_prog = line.getOptionValue("prog", DEFAULT_PROGRAM);
        }
        catch( ParseException exp ) {
            System.err.println( exp.getMessage() );
            System.exit(2);
        }

        Fq2Scribe s = new Fq2Scribe(scribehost, scribeport);
        FqClient []clients = new FqClient[fq_hosts.length];
        for (int i=0; i<fq_hosts.length; i++) {
            String fq_host = fq_hosts[i];
            int fq_port = 8765;
            String []parts = fq_host.split(":");
            if(parts.length == 2) {
	fq_port = Integer.parseInt(parts[1]);
	fq_host = parts[0];
            }
            try {
	FqClient client = new FqClient(new FqConnector(s, fq_exchange, fq_prog));
	client.creds(fq_host, fq_port, fq_source, fq_pass);
	client.connect();
	clients[i] = client;
            }
            catch(com.omniti.labs.FqClientImplInterface.InUseException use) {
	use.printStackTrace();
	System.exit(2);
            }
            catch(java.net.UnknownHostException err) {
	System.err.println(fq_host + ": " + err.getMessage());
	System.exit(2);
            }
        }
        while(true) {
            for (FqClient client : clients) {
	client.send(new FqCommand.StatusRequest());
            }
            try { Thread.sleep(1000); } catch(InterruptedException ignore) { }
        }
    }
}
