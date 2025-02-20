/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix.examples.ordermatch;

import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.MarketDataRequest;
import quickfix.fix42.MarketDataRequestReject;
import quickfix.fix42.MarketDataSnapshotFullRefresh;
import quickfix.fix42.NewOrderSingle;
import quickfix.fix42.OrderCancelRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import quickfix.fix42.OrderCancelReject;

public class Application extends MessageCracker implements quickfix.Application {
    private OrderMatcher orderMatcher = new OrderMatcher();
    private final IdGenerator generator = new IdGenerator();

    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound,
            IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    }

    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound,
            IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        crack(message, sessionId);
    }

    public void onMessage(NewOrderSingle message, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        String senderCompId = message.getHeader().getString(SenderCompID.FIELD);
        String targetCompId = message.getHeader().getString(TargetCompID.FIELD);
        String clOrdId = message.getString(ClOrdID.FIELD);
        String symbol = message.getString(Symbol.FIELD);
        char side = message.getChar(Side.FIELD);
        char ordType = message.getChar(OrdType.FIELD);

        double price = 0;
        if (ordType == OrdType.LIMIT) {
            price = message.getDouble(Price.FIELD);
        }

        double qty = message.getDouble(OrderQty.FIELD);
        char timeInForce = TimeInForce.DAY;
        if (message.isSetField(TimeInForce.FIELD)) {
            timeInForce = message.getChar(TimeInForce.FIELD);
        }

        try {
            if (timeInForce != TimeInForce.DAY) {
                throw new RuntimeException("Unsupported TIF, use Day");
            }

            Order order = new Order(clOrdId, symbol, senderCompId, targetCompId, side, ordType,
                    price, (int) qty);

            processOrder(order, sessionID);
        } catch (Exception e) {
            rejectOrder(targetCompId, senderCompId, clOrdId, symbol, side, e.getMessage());
        }
    }

    private void rejectOrder(String senderCompId, String targetCompId, String clOrdId,
            String symbol, char side, String message) {

        ExecutionReport fixOrder = new ExecutionReport(new OrderID(clOrdId), new ExecID(generator
                .genExecutionID()), new ExecTransType(ExecTransType.NEW), new ExecType(
                ExecType.REJECTED), new OrdStatus(ExecType.REJECTED), new Symbol(symbol), new Side(
                side), new LeavesQty(0), new CumQty(0), new AvgPx(0));

        fixOrder.setString(ClOrdID.FIELD, clOrdId);
        fixOrder.setString(Text.FIELD, message);
        fixOrder.setInt(OrdRejReason.FIELD, OrdRejReason.OTHER);

        try {
            Session.sendToTarget(fixOrder, senderCompId, targetCompId);
        } catch (SessionNotFound e) {
            e.printStackTrace();
        }
    }

    private void processOrder(Order order, SessionID sessionID) {
        if (orderMatcher.insert(order)) {
            acceptOrder(order, sessionID);

            ArrayList<Order> orders = new ArrayList<>();
            orderMatcher.match(order.getSymbol(), orders);

            while (orders.size() > 0) {
                fillOrder(orders.remove(0), sessionID);
            }
            orderMatcher.display(order.getSymbol());
        } else {
            rejectOrder(order, sessionID);
        }
    }

    private void rejectOrder(Order order, SessionID sessionID) {
        updateOrder(order, OrdStatus.REJECTED, sessionID);
    }

    private void acceptOrder(Order order, SessionID sessionID) {
        updateOrder(order, OrdStatus.NEW, sessionID);
    }

    private void cancelOrder(Order order, SessionID sessionID) {
        updateOrder(order, OrdStatus.CANCELED, sessionID);
    }

    private void updateOrder(Order order, char status, SessionID sessionID) {
        String targetCompId = order.getOwner();
        String senderCompId = order.getTarget();

        ExecutionReport fixOrder = new ExecutionReport(new OrderID(order.getClientOrderId()),
                new ExecID(generator.genExecutionID()), new ExecTransType(ExecTransType.NEW),
                new ExecType(status), new OrdStatus(status), new Symbol(order.getSymbol()),
                new Side(order.getSide()), new LeavesQty(order.getOpenQuantity()), new CumQty(order
                        .getExecutedQuantity()), new AvgPx(order.getAvgExecutedPrice()));

        fixOrder.setString(ClOrdID.FIELD, order.getClientOrderId());
        fixOrder.setDouble(OrderQty.FIELD, order.getQuantity());

        if (status == OrdStatus.FILLED || status == OrdStatus.PARTIALLY_FILLED) {
            fixOrder.setDouble(LastShares.FIELD, order.getLastExecutedQuantity());
            fixOrder.setDouble(LastPx.FIELD, order.getPrice());
        }

        try {
            Session.sendToTarget(fixOrder, sessionID);
        } catch (SessionNotFound e) {
            e.printStackTrace();
        }
    }

    private void fillOrder(Order order, SessionID sessionID) {
        updateOrder(order, order.isFilled() ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED, sessionID);
    }

    public void onMessage(OrderCancelRequest message, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        String symbol = message.getString(Symbol.FIELD);
        char side = message.getChar(Side.FIELD);
        String id = message.getString(OrigClOrdID.FIELD);
        Order order = orderMatcher.find(symbol, side, id);
        if (order != null) {
            order.cancel();
            cancelOrder(order, sessionID);
            orderMatcher.erase(order);
        } else {
            OrderCancelReject fixOrderReject = new OrderCancelReject(new OrderID("NONE"), new ClOrdID(message.getString(ClOrdID.FIELD)),
                    new OrigClOrdID(message.getString(OrigClOrdID.FIELD)), new OrdStatus(OrdStatus.REJECTED), new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST));

                    String senderCompId = message.getHeader().getString(SenderCompID.FIELD);
            String targetCompId = message.getHeader().getString(TargetCompID.FIELD);
            fixOrderReject.getHeader().setString(SenderCompID.FIELD, targetCompId);
            fixOrderReject.getHeader().setString(TargetCompID.FIELD, senderCompId);
            try {
                Session.sendToTarget(fixOrderReject, targetCompId, senderCompId);
            } catch (SessionNotFound e) {
            }
        }
    }

    public void onMessage(MarketDataRequest message, SessionID sessionID) throws FieldNotFound,
            UnsupportedMessageType, IncorrectTagValue {
        MarketDataRequest.NoRelatedSym noRelatedSyms = new MarketDataRequest.NoRelatedSym();

        //String mdReqId = message.getString(MDReqID.FIELD);
        char subscriptionRequestType = message.getChar(SubscriptionRequestType.FIELD);

        if (subscriptionRequestType != SubscriptionRequestType.SNAPSHOT)
            throw new IncorrectTagValue(SubscriptionRequestType.FIELD);
        //int marketDepth = message.getInt(MarketDepth.FIELD);
        int relatedSymbolCount = message.getInt(NoRelatedSym.FIELD);

        MarketDataSnapshotFullRefresh fixMD = new MarketDataSnapshotFullRefresh();
        fixMD.setString(MDReqID.FIELD, message.getString(MDReqID.FIELD));
        
        for (int i = 1; i <= relatedSymbolCount; ++i) {
            message.getGroup(i, noRelatedSyms);
            String symbol = noRelatedSyms.getString(Symbol.FIELD);
            fixMD.setString(Symbol.FIELD, symbol);
            
            Market market = orderMatcher.getMarket(symbol);
            Stream.concat(market.getBidOrders().stream(), market.getAskOrders().stream()).forEach(ord -> {
                MarketDataSnapshotFullRefresh.NoMDEntries noMDEntries = new MarketDataSnapshotFullRefresh.NoMDEntries();
                noMDEntries.set(new OrderID(ord.getClientOrderId()));
                noMDEntries.set(new MDEntryType(ord.getSide() == Side.BUY ? MDEntryType.BID : MDEntryType.OFFER));
                noMDEntries.set(new MDEntryPx(ord.getPrice()));
                noMDEntries.set(new MDEntrySize(ord.getOpenQuantity()));
                noMDEntries.set(new MDMkt("QF")); // QF: QuickFix.
                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ord.getEntryTime()), ZoneId.systemDefault());
                noMDEntries.set(new MDEntryDate(dateTime.toLocalDate()));
                noMDEntries.set(new MDEntryTime(dateTime.toLocalTime()));
                fixMD.addGroup(noMDEntries);
            });
        }
        
        String senderCompId = message.getHeader().getString(SenderCompID.FIELD);
        String targetCompId = message.getHeader().getString(TargetCompID.FIELD);
        fixMD.getHeader().setString(SenderCompID.FIELD, targetCompId);
        fixMD.getHeader().setString(TargetCompID.FIELD, senderCompId);
        try {
            Message sentMessage = fixMD;
            if (!fixMD.hasGroup(new MarketDataSnapshotFullRefresh.NoMDEntries().getFieldTag())) {
                MarketDataRequestReject reject = new MarketDataRequestReject(new MDReqID(message.getString(MDReqID.FIELD)));
                reject.getHeader().setString(SenderCompID.FIELD, targetCompId);
                reject.getHeader().setString(TargetCompID.FIELD, senderCompId);
                sentMessage = reject;
            }
            Session.sendToTarget(sentMessage, sessionID);
        } catch (SessionNotFound e) {
            e.printStackTrace();
        }
    }

    public void onCreate(SessionID sessionId) {
    }

    public void onLogon(SessionID sessionId) {
        System.out.println("Logon - " + sessionId);
    }

    public void onLogout(SessionID sessionId) {
        System.out.println("Logout - " + sessionId);
    }

    public void toAdmin(Message message, SessionID sessionId) {
        // empty
    }

    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        // empty
    }

    public OrderMatcher orderMatcher() {
        return orderMatcher;
    }
    
    public void setOrderMatcher(OrderMatcher orderMatcher) {
        this.orderMatcher = orderMatcher;
    }
}
