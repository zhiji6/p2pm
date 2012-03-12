package pl.edu.pjwstk.p2pp.transactions;

import java.util.Arrays;
import java.util.Vector;

import org.apache.log4j.Logger;
import pl.edu.pjwstk.p2pp.entities.P2PPEntity;
import pl.edu.pjwstk.p2pp.messages.Acknowledgment;
import pl.edu.pjwstk.p2pp.messages.Indication;
import pl.edu.pjwstk.p2pp.messages.Message;
import pl.edu.pjwstk.p2pp.messages.P2PPMessage;
import pl.edu.pjwstk.p2pp.messages.requests.Request;
import pl.edu.pjwstk.p2pp.messages.responses.Response;
import pl.edu.pjwstk.p2pp.objects.AddressInfo;
import pl.edu.pjwstk.p2pp.util.ByteUtils;

/**
 * <p>
 * For requests, a transaction constitutes of a single request followed by acknowledgments (if any), and a response. For
 * responses, reponseACKs, and indications, a transaction consists of single response, responseACK, or indication,
 * followed by an ACK if unreliable transport is used.
 * </p>
 * <p>
 * It is a little more than in P2PP specification. There, it is a transaction with remote entity having one address.
 * Here, it is a transaction with a set of addresses. If communication with one turns to failed state, another one is
 * used. If there's no more addresses, transaction fails completely and an entity is informed.
 * </p>
 *
 * @author Maciej Skorupka s3874@pjwstk.edu.pl
 */
public class Transaction {

    private static Logger LOG = Logger.getLogger(Transaction.class);

    /**
     * In milliseconds.
     */
    public static final int T0 = 1000;
    /**
     * In milliseconds.
     */
    public static final int T1 = T0;
    /**
     * In milliseconds.
     */
    public static final int T2 = 5000;

    public static final int T3 = 60000;

    /**
     * Constant for transaction of request type.
     */
    public static final byte REQUEST_TRANSACTION_TYPE = 1;
    /**
     * Constant for transaction of response type.
     */
    public static final byte RESPONSE_TRANSACTION_TYPE = 2;
    /**
     * Constant for transaction of responseACK type.
     */
    public static final byte RESPONSE_ACK_TRANSACTION_TYPE = 3;
    /**
     * Constant for transaction of indication type.
     */
    public static final byte INDICATION_TRANSACTION_TYPE = 4;

    /**
     * Constant for initial state.
     */
    public static final byte INITIAL_STATE = 0;
    /**
     * Constant for trans_msg (transmit message) state.
     */
    public static final byte TRANS_MSG_STATE = 1;
    /**
     * Constant for wait_resp (if a request was sent and an acknowledgment was received state.
     */
    public static final byte WAIT_RESP_STATE = 2;
    /**
     * Constant for terminated (if a response, responseACK, or an indication was sent and ACK was received) state.
     */
    public static final byte TERMINATED_STATE = 3;
    /**
     * Constant for failure state.
     */
    public static final byte FAILURE_STATE = 4;

    public static final byte TRANSPORT_FAILURE_STATE = 5;

    /**
     * Transaction ID generated by transaction table.
     */
    private byte[] transactionID;

    private byte state = INITIAL_STATE;

    /**
     * Transaction type (such as Request, RequestACK, Response, Indication).
     */
    private byte type;

    /**
     * True if reliable transport is used. False otherwise.
     */
    private boolean reliable;

    private Response response;
    private Request request;
    private Indication indication;
    /**
     * Received ACK. If null, there is wasn't any ACK received for this transaction.
     */
    private Acknowledgment receivedAck;

    /**
     * Vector of ACKs to be send.
     */
    private Vector<Acknowledgment> ackVectorToSend = new Vector<Acknowledgment>();
    /**
     * Number of sent ACKs. If smaller than size of ACKs vector, ACKs have to be send.
     */
    private int acksSent = 0;

    private byte[] sourceID;

    private byte[] ownPeerID;

    /**
     * Listener that wants to know about how this transaction ended.
     */
    private TransactionListener transactionListener;
    /**
     * Vector of addresses (AddressInfo objects) of a remote entity.
     */
    private Vector<AddressInfo> addressInfos;

    /**
     * Index of current address in addressInfos field. Incremented when communication with current address (placed on
     * currentAddressIndex in addressInfos) wasn't completed.
     */
    private int currentAddressIndex = 0;

    // timers stuff
    /**
     * Moment in which this transaction has started. Initiated on first {@link #onTimeSlot(TransactionTable, pl.edu.pjwstk.p2pp.entities.P2PPEntity)} ()} invocation.
     */
    @SuppressWarnings("unused")
	private long momentOfStart;
    /**
     * Timer0 (T0) as defined in P2PP specification (draft 01). Holds time value of the moment on which the transaction
     * was created.
     */
    @SuppressWarnings("unused")
	private long timer0;
    /**
     * Timer1 containing a moment in time in which a retransmission should happen.
     */
    private long timer1;
    /**
     * Timer2 containing a moment in which transaction with one address fails.
     */
    private long timer2;
    /**
     * Timer3
     */
    private long timer3;

    // end of timers stuff

    /**
     * Constructor of transaction for general message.
     *
     * @param message             P2PPMessage for which this transaction is created. Type of this message determines a type of
     *                            transaction (i.e. request message creates a transaction of {@link #REQUEST_TRANSACTION_TYPE} type,
     *                            response creates a transaction of {@link #RESPONSE_TRANSACTION_TYPE} type, responseACK a transaction
     *                            of {@link #RESPONSE_ACK_TRANSACTION_TYPE} type and indication creates a transaction of
     *                            {@link #INDICATION_TRANSACTION_TYPE}.
     * @param transactionListener Will be used to inform about ended transactions.
     * @param addressInfos        Vector of AddressInfo objects containing addresses of remote entity with which this transaction is.
     * @param ownPeerID           Used for creating ACKs.
     * @param transactionID
     */
    public Transaction(P2PPMessage message, TransactionListener transactionListener, Vector<AddressInfo> addressInfos,
                       byte[] ownPeerID, byte[] transactionID) {
        this.transactionListener = transactionListener;
        this.addressInfos = addressInfos;
        this.reliable = message.isOverReliable();
        this.ownPeerID = ownPeerID;
        this.transactionID = transactionID;

        this.sourceID = message.getSourceID();

        if (message instanceof Indication) {
            this.indication = (Indication) message;
            this.type = INDICATION_TRANSACTION_TYPE;
        } else if (message instanceof Request) {
            this.request = (Request) message;
            this.type = REQUEST_TRANSACTION_TYPE;
        } else if (message instanceof Response) {
            this.response = (Response) message;
            if (response.isResponseACK()) {
                this.type = RESPONSE_ACK_TRANSACTION_TYPE;
            } else {
                this.type = RESPONSE_TRANSACTION_TYPE;
            }
        }

    }

    @Override
    public String toString() {
        StringBuilder strb = new StringBuilder("Transaction=[ID=");
        strb.append(ByteUtils.byteArrayToHexString(this.transactionID));
        strb.append("; type=");
        strb.append(this.type);
        strb.append("; sourceID=");
        strb.append(ByteUtils.byteArrayToHexString(this.sourceID));
        strb.append("; ownPeerID=");
        strb.append(this.ownPeerID);
        strb.append("; addressInfos=");
        strb.append(this.addressInfos);
        strb.append("]");
        return strb.toString();
    }

    /**
     * Returns sourceID of this transaction.
     *
     * @return
     */
    public byte[] getSourceID() {
        return sourceID;
    }

    /**
     * Sets request to this transaction.
     *
     * @param request
     */
    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Sets response for this transaction.
     *
     * @param response
     */
    public void setResponse(Response response) {
        this.response = response;
    }

    /**
     * Sets received acknowledgment.
     *
     * @param acknowledgment Acknowledgment to be added to transaction.
     */
    public void setReceivedAck(Acknowledgment acknowledgment) {
        receivedAck = acknowledgment;
    }

    /**
     * Sets indication for this transaction.
     *
     * @param indication
     */
    public void setIndication(Indication indication) {
        this.indication = indication;
    }

    /**
     * Returns transactionID as bytes array (4 bytes long).
     *
     * @return TransactionID.
     */
    public byte[] getTransactionID() {
        return transactionID;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Transaction)) {
            return false;
        } else {
            Transaction transaction = (Transaction) obj;
            if (Arrays.equals(sourceID, transaction.getSourceID())
                    && Arrays.equals(transactionID, transaction.getTransactionID())) {
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * <p>
     * Method invoked when this transaction has time for checking its timers. If this transaction wants to send a
     * message (for instance a retransmission), it returns it here. It isn't obligatory, so returned value may be null.
     * </p>
     * <p>
     * If it's the first time this transaction gets a time slot (i.e. it is in initial state), message is returned.
     * </p>
     *
     * @param transactionTable Transaction table to be used when transaction ends but wants to create new transaction. This happens
     *                         when request transaction was over unreliable transport and next hop response was received. In this
     *                         case, transaction with next hop should be created.
     * @param localEntity
     */
    public Message onTimeSlot(TransactionTable transactionTable, P2PPEntity localEntity) {
        long currentTime = System.currentTimeMillis();

        Message resultMessage = null;
        // if transaction is over reliable transport
        if (reliable) {
            // if transaction is request type
            if (type == REQUEST_TRANSACTION_TYPE) {
                resultMessage = timeSlotForReliableRequest(transactionTable, currentTime, localEntity);
            } else {
                resultMessage = timeSlotForReliableNonRequest(transactionTable, currentTime, localEntity);
            }

        } else {

            // if there is an ACK to send
            if (acksSent < ackVectorToSend.size()) {
                // TODO something with timers?
                // returns waiting ACK and moves counts that this ACK was sent
                resultMessage = ackVectorToSend.get(acksSent++);
            } else {
                // if transaction is request type
                if (type == REQUEST_TRANSACTION_TYPE) {
                    resultMessage = timeSlotForUnreliableRequest(transactionTable, currentTime, localEntity);
                } else if (type == RESPONSE_ACK_TRANSACTION_TYPE || type == RESPONSE_TRANSACTION_TYPE
                        || type == INDICATION_TRANSACTION_TYPE) {
                    resultMessage = timeSlotForUnreliableNonRequest(transactionTable, currentTime, localEntity);
                }
            }
        }

        return resultMessage;
    }

    /**
     * TODO Time slot for a non-request transaction over unreliable transport.
     *
     * @param transactionTable
     * @param currentTime
     * @return
     */
    private Message timeSlotForUnreliableNonRequest(TransactionTable transactionTable, long currentTime, P2PPEntity localEntity) {

        if (LOG.isTraceEnabled()) {
            LOG.trace("timeSlotForUnreliableNonRequest: state=" + state);
        }

        // TODO check the whole method
        Message resultMessage = null;
        switch (state) {
            case INITIAL_STATE: {
                if (response != null) {
                    resultMessage = response;
                } else if (indication != null) {
                    resultMessage = indication;
                } // is this all?
                // fills message with current address of remote entity
                AddressInfo currentAddress = addressInfos.get(currentAddressIndex);
                if (resultMessage != null && currentAddress != null) {
                    resultMessage.setReceiverAddress(currentAddress.getAddress());
                    resultMessage.setReceiverPort(currentAddress.getPort());
                }

                // sets timers for retransmissions and failure
                timer1 = currentTime + T1;
                timer2 = currentTime + T2;
                // changes state to transmitting
                state = TRANS_MSG_STATE;
                break;
            }
            case TRANS_MSG_STATE: {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("timeSlotForUnreliableNonRequest: state=TRANS; receivedAck=" + receivedAck);
                }
                if (receivedAck != null) {
                    // this line was at first, but created ACK (it seems bad in this case)
                    // resultMessage = response.createACK(ownPeerID);
                    state = TERMINATED_STATE;
                    transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable,
                            localEntity);
                } // if retransmission should happen
                else if (currentTime > timer1) {
                    resultMessage = request;
                    timer1 = currentTime + T1;
                }
                // if communication with current address has failed
                else if (currentTime > timer2) {
                    timer2 = currentTime + T2;
                    // moves to next address of remote entity
                    currentAddressIndex++;
                    // if this was last known address of remote entity
                    if (currentAddressIndex >= addressInfos.size()) {
                        state = FAILURE_STATE;
                        transactionListener.transactionEnded(transactionID, state, type, null, null, transactionTable,
                                localEntity);
                    } else {
                        // turns back to initial state but address will be
                        // different
                        state = INITIAL_STATE;
                    }
                }
                break;
            }
            // probably this state doesn't exist for non-request transaction
            case WAIT_RESP_STATE: {
                break;
            }
            case TERMINATED_STATE:
                break;
            case FAILURE_STATE:
                break;
        }
        return resultMessage;
    }

    /**
     * TODO Time slot for a non-request transaction over reliable transport.
     *
     * @param transactionTable
     * @param currentTime
     * @return
     */
    private Message timeSlotForReliableNonRequest(TransactionTable transactionTable, long currentTime, P2PPEntity localEntity) {

        if (LOG.isTraceEnabled()) {
            LOG.trace("timeSlotForReliableNonRequest: state=" + state);
        }

        // TODO check the whole method
        Message resultMessage = null;
        switch (state) {
            case INITIAL_STATE: {

                if (response != null) {
                    resultMessage = response;
                } else if (indication != null) {
                    resultMessage = indication;
                } // is this all?
                
                // fills message with current address of remote entity
                AddressInfo currentAddress = addressInfos.get(currentAddressIndex);
                if (resultMessage != null && currentAddress != null) {
                    resultMessage.setReceiverAddress(currentAddress.getAddress());
                    resultMessage.setReceiverPort(currentAddress.getPort());
                }
                
                state = TRANS_MSG_STATE;

                break;
            }
            case TRANS_MSG_STATE: {

                if (LOG.isTraceEnabled()) {
                    LOG.trace("timeSlotForReliableNonRequest: state=TRANS");
                }

                state = TERMINATED_STATE;
                transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable, localEntity);

                break;
            }
            // probably this state doesn't exist for non-request transaction
            case WAIT_RESP_STATE: {
                break;
            }
            case TRANSPORT_FAILURE_STATE: {

                        if (++currentAddressIndex < addressInfos.size()) {
                            // Trying next address
                            state = INITIAL_STATE;
                        } else {
                            // All hope is lost
                            state = FAILURE_STATE;
                            transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable, localEntity);
                        }

                break;
            }
            case TERMINATED_STATE:
                break;
            case FAILURE_STATE:
                break;
        }
        return resultMessage;

    }

    /**
     * TODO Time slot for a request transaction over reliable transport.
     *
     * @param transactionTable
     * @param currentTime
     * @return
     */
    private Message timeSlotForReliableRequest(TransactionTable transactionTable, long currentTime,
                                               P2PPEntity localEntity) {

        if (LOG.isTraceEnabled()) {
            LOG.trace("timeSlotForReliableRequest: state=" + state);
        }

        Message resultMessage = null;
        switch (state) {
            case INITIAL_STATE: {

                if (request != null) {
                    resultMessage = request;
                }// TODO probably those elses may be deleted because it's request transaction type
                else if (response != null) {
                    resultMessage = response;
                } else if (indication != null) {
                    resultMessage = indication;
                } // is this all?
                // fills message with current address of remote entity

                if (resultMessage != null) {
                    AddressInfo currentAddress = addressInfos.get(currentAddressIndex);
                    resultMessage.setReceiverAddress(currentAddress.getAddress());
                    resultMessage.setReceiverPort(currentAddress.getPort());
                }

                state = TRANS_MSG_STATE;
                break;
            }
            case TRANS_MSG_STATE: {

                if (LOG.isTraceEnabled()) {
                    LOG.trace("timeSlotForReliableRequest - TRANS_MSG: response=" + response);
                }

                timer3 = currentTime + T3;
                state = WAIT_RESP_STATE;
                break;
            }
            case WAIT_RESP_STATE: {

                if (LOG.isTraceEnabled()) {
                    LOG.trace("timeSlotForReliableRequest - WAIT_RESP_STATE: response=" + response);
                }

                // if response was received
                if (response != null) {
                    // checks if response is not OK (200)... if next hop, new transaction is created for it
                    /*
                      * int responseCode = response.getResponseCodeAsInt(); if (responseCode ==
                      * Response.RESPONSE_CODE_NEXT_HOP) { NextHopResponse nextHopResponse = (NextHopResponse) response;
                      * transactionTable.createTransactionAndFill(request, transactionListener, nextHopResponse
                      * .getNextHopPeerInfo().getAddressInfos(), ownPeerID, nextHopResponse.getNextHopPeerInfo()
                      * .getPeerID().getPeerIDBytes()); }
                      */
                    //resultMessage = response.createACK(ownPeerID);
                    state = TERMINATED_STATE;

                    transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable,
                            localEntity);

                    response = null;

                } else {

                    if (currentTime > timer3) {
                        timer3 = currentTime + T3;

                        if (++currentAddressIndex < addressInfos.size()) {
                            // Trying next address
                            state = INITIAL_STATE;
                        } else {
                            // All hope is lost
                            state = FAILURE_STATE;
                            transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable, localEntity);
                        }
                    }

                }

                break;
            }
            case TRANSPORT_FAILURE_STATE: {

                        if (++currentAddressIndex < addressInfos.size()) {
                            // Trying next address
                            state = INITIAL_STATE;
                        } else {
                            // All hope is lost
                            state = FAILURE_STATE;
                            transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable, localEntity);
                        }

                break;
            }
            case TERMINATED_STATE:
                break;
            case FAILURE_STATE:
                break;
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("timeSlotForReliableRequest: postState=" + state);
        }

        return resultMessage;
    }

    /**
     * Time slot for a request transaction over unreliable transport.
     *
     * @param transactionTable
     * @param currentTime
     * @return
     */
    private Message timeSlotForUnreliableRequest(TransactionTable transactionTable, long currentTime, P2PPEntity localEntity) {

        if (LOG.isTraceEnabled()) {
            LOG.trace("timeSlotForUnreliableRequest: state=" + state);
        }

        Message resultMessage = null;
        switch (state) {
            case INITIAL_STATE: {
                if (request != null) {
                    resultMessage = request;
                }// TODO probably those elses may be deleted because it's request transaction type
                else if (response != null) {
                    resultMessage = response;
                } else if (indication != null) {
                    resultMessage = indication;
                } // is this all?
                // fills message with current address of remote entity

                if (resultMessage != null) {
                    AddressInfo currentAddress = addressInfos.get(currentAddressIndex);
                    resultMessage.setReceiverAddress(currentAddress.getAddress());
                    resultMessage.setReceiverPort(currentAddress.getPort());
                }

                // sets timers for retransmissions and failure
                timer1 = currentTime + T1;
                timer2 = currentTime + T2;
                // changes state to transmitting
                state = TRANS_MSG_STATE;
                break;
            }
            case TRANS_MSG_STATE: {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("timeSlotForUnreliableRequest - TRANS_MSG: receivedAck=" + receivedAck + "; response=" + response);
                }
                // if there's received ACK for this transaction
                if (receivedAck != null) {
                    receivedAck = null;
                    state = WAIT_RESP_STATE;
                } // if there's a responseACK for this transaction
                else if (response != null) {

                    // checks if response is not OK (200)... if next hop, new transaction is created for it
                    /*
                      * int responseCode = response.getResponseCodeAsInt(); if (responseCode ==
                      * Response.RESPONSE_CODE_NEXT_HOP) { NextHopResponse nextHopResponse = (NextHopResponse) response;
                      * transactionTable.createTransactionAndFill(request, transactionListener, nextHopResponse
                      * .getNextHopPeerInfo().getAddressInfos(), ownPeerID, nextHopResponse.getNextHopPeerInfo()
                      * .getPeerID().getPeerIDBytes()); }
                      */

                    resultMessage = response.createACK(ownPeerID);
                    state = TERMINATED_STATE;
                    transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable,
                            localEntity);
                    response = null;
                } // if retransmission should happen
                else if (currentTime > timer1) {
                    resultMessage = request;
                    timer1 = currentTime + T1;
                }
                // if communication with current address has failed
                else if (currentTime > timer2) {
                    timer2 = currentTime + T2;
                    // moves to next address of remote entity
                    currentAddressIndex++;
                    // if this was last known address of remote entity
                    if (currentAddressIndex >= addressInfos.size()) {
                        state = FAILURE_STATE;
                        transactionListener.transactionEnded(transactionID, state, type, request, response,
                                transactionTable, localEntity);
                    } else {
                        // turns back to initial state but address will be different
                        state = INITIAL_STATE;
                    }
                }
                break;
            }
            case WAIT_RESP_STATE: {
                // if response was received
                if (response != null) {
                    // checks if response is not OK (200)... if next hop, new transaction is created for it
                    /*
                      * int responseCode = response.getResponseCodeAsInt(); if (responseCode ==
                      * Response.RESPONSE_CODE_NEXT_HOP) { NextHopResponse nextHopResponse = (NextHopResponse) response;
                      * transactionTable.createTransactionAndFill(request, transactionListener, nextHopResponse
                      * .getNextHopPeerInfo().getAddressInfos(), ownPeerID, nextHopResponse.getNextHopPeerInfo()
                      * .getPeerID().getPeerIDBytes()); }
                      */
                    resultMessage = response.createACK(ownPeerID);
                    state = TERMINATED_STATE;

                    transactionListener.transactionEnded(transactionID, state, type, request, response, transactionTable,
                            localEntity);

                    response = null;
                }
                break;
            }
            case TERMINATED_STATE:
                break;
            case FAILURE_STATE:
                break;
        }
        return resultMessage;
    }

    /**
     * Returns type of this transaction. There are constants in this class for available types (
     * {@link #REQUEST_TRANSACTION_TYPE}, {@link #RESPONSE_TRANSACTION_TYPE}, {@link #RESPONSE_ACK_TRANSACTION_TYPE},
     * {@link #INDICATION_TRANSACTION_TYPE}).
     *
     * @return
     */
    public byte getType() {
        return type;
    }

    /**
     * Returns a state of transaction. Transaction may be in {@link #INITIAL_STATE}, {@link #TRANS_MSG_STATE},
     * {@link #WAIT_RESP_STATE}, {@link #TERMINATED_STATE} or {@link #FAILURE_STATE}.
     *
     * @return
     */
    public byte getState() {
        return state;
    }

    public void setState(byte state) {
        if (state < 6) {
            this.state = state;
        }
    }

    /**
     * Adds an acknowledgement to be send.
	 *
	 * @param acknowledgment
	 */
	public void addAckToSend(Acknowledgment acknowledgment) {
		ackVectorToSend.add(acknowledgment);
	}
}
