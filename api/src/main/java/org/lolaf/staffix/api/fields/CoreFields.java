/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.api.fields;

/**
 * The tags the session layer works in, and the SOH byte that separates every field.
 *
 * <p>Constants rather than registry lookups because the session layer reads these before it knows which
 * dictionary applies - BeginString(8), BodyLength(9) and MsgType(35) have to be parsed to find that out.
 */
public interface CoreFields {

    /**
     * The separator of a FIX message fields
     */
    char FIELD_SEPARATOR = '\001';
    byte FIELD_SEPARATOR_BYTE = FIELD_SEPARATOR;

    int TEXT = 58;
    int ENCRYPT_METHOD = 98;
    int HEARTBEAT_INTERVAL = 108;
    int TEST_REQUEST_ID = 112;
    int SENDING_TIME = 52;
    int ORIG_SENDING_TIME = 122;
    int POSS_DUP_FLAG = 43;
    int POSS_RESEND = 97;
    int RESET_NUM_FLAG = 141;
    int TEST_MESSAGE_INDICATOR = 464;
    int CHECKSUM = 10;
    int BEGIN_STRING = 8;
    int BODY_LENGTH = 9;
    int MESSAGE_TYPE = 35;
    int MESSAGE_SEQ_NUM = 34;
    int NEXT_EXPECTED_MSG_SEQ_NUM = 789;
    int GAP_FILL = 123;
    int NEW_SEQ_NO = 36;
    int MAX_MESSAGE_SIZE = 383;

    int BEGIN_SEQ_NO = 7;
    int END_SEQ_NO = 16;

    int USERNAME = 553;
    int PASSWORD = 554;

    int SENDER_COMP_ID = 49;
    int SENDER_SUB_ID = 50;
    int SENDER_LOCATION_ID = 142;
    int TARGET_COMP_ID = 56;
    int TARGET_SUB_ID = 57;
    int TARGET_LOCATION_ID = 143;
    int ON_BEHALF_OF_COMP_ID = 115;
    int DELIVER_TO_COMP_ID = 128;

    int APPL_VER_ID = 1128;
    int DEFAULT_APPL_VER_ID = 1137;

}
