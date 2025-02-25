/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.ability;

import io.enmasse.systemtest.AddressSpaceType;
import io.enmasse.systemtest.AddressType;
import io.enmasse.systemtest.DestinationPlan;
import org.junit.jupiter.api.Tag;

import static io.enmasse.systemtest.TestTag.sharedStandard;

@Tag(sharedStandard)
public interface ITestBaseStandard extends ITestBase {

    @Override
    default AddressSpaceType getAddressSpaceType() {
        return AddressSpaceType.STANDARD;
    }

    @Override
    default String getDefaultPlan(AddressType addressType) {
        switch (addressType) {
            case QUEUE:
                return DestinationPlan.STANDARD_SMALL_QUEUE;
            case TOPIC:
                return DestinationPlan.STANDARD_SMALL_TOPIC;
            case ANYCAST:
                return DestinationPlan.STANDARD_SMALL_ANYCAST;
            case MULTICAST:
                return DestinationPlan.STANDARD_SMALL_MULTICAST;
        }
        return null;
    }

}
