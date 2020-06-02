package com.amazon.test;

import io.grpc.*;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class Server {
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    private io.grpc.Server server;
    public void start() throws IOException {
        /* The port on which the server should run */
        int port = 50051;
        server = ServerBuilder.forPort(port)
                .addService(new GreeterImpl())
                .addTransportFilter(new TransportFilter())
                .intercept(new ServerIntercept())
                .build()
                .start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                    Server.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
            }
        });
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }


    /**
     * Main launches the server from the command line.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final Server server = new Server();
        server.start();
        server.blockUntilShutdown();
    }

    public class GreeterImpl extends GreeterGrpc.GreeterImplBase {
        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            logger.info("Sending hello response: ");
            HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }

    public class TransportFilter extends ServerTransportFilter {
        @Override
        public Attributes transportReady(Attributes transportAttrs) {
            logger.info("Transport Ready, remote addr " + transportAttrs);
            return super.transportReady(transportAttrs);
        }


        @Override
        public void transportTerminated(Attributes transportAttrs) {
            logger.info("Transport Terminated: ");
            super.transportTerminated(transportAttrs);
        }
    }


    public class ServerIntercept implements ServerInterceptor {
        public int count = 0;

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(next.startCall(call, headers)) {
                @Override
                public void onMessage(ReqT message) {
                    logger.info("REMOTE IP IN INTERCEPT: " + call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
                    if (count++ > 5) {
                        // MUST AVOID calling call.close multiple times. This code could run in race-condition.
                        // Ensure a mutex of some sort used so that this code run once per given TCP Connection
                        try {
                            call.close(Status.PERMISSION_DENIED.withDescription("Use new cell of XYZ"), headers);
                        } catch (IllegalStateException e) {
                            // disregard, as we called close twice. NOTE SHOULD AVOID this in prod
                        }
                        return;
                    }
                    super.onMessage(message);
                }

            };

        }
    }
}