#!/bin/sh
#
# Copyright © 2024-2026 Lolaf.org
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

. "$(dirname "$0")/common.sh"
case "$1" in
  1 | 2) id=$1 ;;
  leader) id=$(node_with_role LEADER) || exit 1 ;;
  follower) id=$(node_with_role FOLLOWER) || exit 1 ;;
  *) echo "usage: $0 <1|2|leader|follower> [-9]" >&2; exit 2 ;;
esac
stop "node-$id" "$2"
