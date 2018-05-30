/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Wal-Mart Store, Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */
import * as React from 'react';
import { Icon, Popup, SemanticICONS, SemanticCOLORS } from 'semantic-ui-react';

import { ProcessStatus } from '../../../api/process';
type IconSizeProp = 'mini' | 'tiny' | 'small' | 'large' | 'big' | 'huge' | 'massive';

const statusToIcon: {
    [status: string]: { name: SemanticICONS; color?: SemanticCOLORS; loading?: boolean };
} = {
    PREPARING: { name: 'info', color: 'blue' },
    ENQUEUED: { name: 'block layout', color: 'grey' },
    RESUMING: { name: 'circle notched', color: 'grey', loading: true },
    SUSPENDED: { name: 'wait', color: 'blue' },
    STARTING: { name: 'circle notched', color: 'grey', loading: true },
    RUNNING: { name: 'circle notched', color: 'blue', loading: true },
    FINISHED: { name: 'check', color: 'green' },
    FAILED: { name: 'remove', color: 'red' },
    CANCELLED: { name: 'remove', color: 'grey' }
};

interface ProcessStatusIconProps {
    status: ProcessStatus;
    size?: IconSizeProp;
    inverted?: boolean;
}

export default ({ status, size = 'large', inverted = true }: ProcessStatusIconProps) => {
    // TODO: Fix status to reference number..? reference number
    let i = statusToIcon[status];

    if (!i) {
        i = { name: 'question' };
    }

    return (
        <Popup
            trigger={<Icon name={i.name} color={i.color} size={size} loading={i.loading} />}
            content={status}
            inverted={inverted}
            position="top center"
        />
    );
};