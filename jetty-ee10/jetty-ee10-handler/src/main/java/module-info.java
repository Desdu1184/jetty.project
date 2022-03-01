//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

module org.eclipse.jetty.ee10.handler
{
    requires transitive jetty.servlet.api;
    requires transitive org.eclipse.jetty.http;
    requires transitive org.slf4j;

    // Only required if using DatabaseAdaptor/JDBCSessionDataStore.
    requires static java.sql;
    requires static java.naming;
    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;

    exports org.eclipse.jetty.ee10.handler;
    exports org.eclipse.jetty.ee10.handler.gzip;
    exports org.eclipse.jetty.ee10.handler.jmx to
        org.eclipse.jetty.jmx;
}
