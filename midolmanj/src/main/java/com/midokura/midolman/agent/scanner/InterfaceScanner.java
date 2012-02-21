/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.agent.scanner;

import java.util.List;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;

/**
 * Interface data scanning module api. It's job is to return an up-to-date list
 * of local interface data when called. Blocking.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public interface InterfaceScanner {

    /**
     * Do a scan and find out the current list of interface data from the local
     * system.
     *
     * @return list of interfaces
     */
    List<InterfaceDescription> scanInterfaces();
}
