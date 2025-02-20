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

import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.ScreenLogFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        InputStream inputStream = null;
        try {
            if (args.length == 0) {
                inputStream = OrderMatcher.class.getResourceAsStream("ordermatch.cfg");
            } else if (args.length == 1) {
                inputStream = new FileInputStream(args[0]);
            }
            if (inputStream == null) {
                System.out.println("usage: " + OrderMatcher.class.getName() + " [configFile].");
                return;
            }
            SessionSettings settings = new SessionSettings(inputStream);

            Application application = new Application();
            FileStoreFactory storeFactory = new FileStoreFactory(settings);
            LogFactory logFactory = new ScreenLogFactory(settings);
            SocketAcceptor acceptor = new SocketAcceptor(application, storeFactory, settings,
                    logFactory, new DefaultMessageFactory());
            // 恢复订单簿
            String serializedFile = settings.getString(FileStoreFactory.SETTING_FILE_STORE_PATH) + File.separator + "ordermatch.bin";
            if (new File(serializedFile).exists()) {
                OrderMatcher orderMatcher = (OrderMatcher) SerializationUtils.deserializeFromFile(serializedFile);
                application.setOrderMatcher(orderMatcher);
                System.out.println(LocalDateTime.now() + ": Restored ordermatch");
            }
            
            // 定期保存订单簿
            new ScheduledThreadPoolExecutor(1).scheduleAtFixedRate(() -> {
	            try {
		            serializeOrderMatch(application, settings);
	            } catch (Exception e) {
		            e.printStackTrace();
	            }
            }, 60, 60, TimeUnit.SECONDS);
            
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            acceptor.start();
            label:
            while (true) {
                System.out.println("type #quit to quit");
                String value = in.readLine();
                if (value != null) {
                    switch (value) {
                        case "#symbols":
                            application.orderMatcher().display();
                            break;
                        case "#quit":
                            break label;
                        default:
                            application.orderMatcher().display();
                            serializeOrderMatch(application, settings);
                            break;
                    }
                }
            }
            acceptor.stop();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException ex) {
                // ignore on close
            }
        }
    }

    private static void serializeOrderMatch(Application application, SessionSettings settings) throws Exception {
        SerializationUtils.serializeToFile(application.orderMatcher(),
                settings.getString(FileStoreFactory.SETTING_FILE_STORE_PATH) +
                File.separator + "ordermatch.bin");
        System.out.println(LocalDateTime.now() + ": Saved ordermatch");
    }
}
