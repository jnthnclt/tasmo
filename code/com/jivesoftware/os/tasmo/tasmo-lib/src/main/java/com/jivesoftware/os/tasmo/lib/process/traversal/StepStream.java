/*
 * $Revision$
 * $Date$
 *
 * Copyright (C) 1999-$year$ Jive Software. All rights reserved.
 *
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package com.jivesoftware.os.tasmo.lib.process.traversal;

import com.jivesoftware.os.tasmo.lib.write.PathId;

/**
 *
 * @author jonathan.colt
 */
public interface StepStream {

    void stream(PathId pathId) throws Exception; // TODO: Consider batching?

    int getStepIndex();
}
