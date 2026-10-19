# OsmAnd external API

Pinned API protocol classes only, not the OsmAnd renderer/SDK/application.

Downloaded 2026-10-19 from the official builder:
https://builder.osmand.net/ivy/net.osmand/android-aidl-lib/master-snapshot/android-aidl-lib-master-snapshot.aar
AAR SHA256: 22fd19e905d1f7b0636f70044ea86d6c3caefd994280733d83638947498c3051
The runtime JAR is the unmodified classes.jar from this archive. The matching official source archive is included for inspectability; it is not a runtime dependency.
Source URL: https://builder.osmand.net/ivy/net.osmand/android-aidl-lib/master-snapshot/android-aidl-lib-master-snapshot-sources.jar
Source SHA256: fea59f3b5479831c22a19916fb5ddb508ff32d7ef0e3e92714a2f539891ab813
Upstream code and license: https://github.com/osmandapp/OsmAnd/tree/master/OsmAnd-api ; OSMAND-LICENSE.txt.
The pinned files avoid a moving snapshot dependency at build time. Updating them is an explicit dependency change.

Official integration example: https://github.com/osmandapp/osmand-api-demo .
API V2 binds net.osmand.aidl.OsmandAidlServiceV2. PH owns one temporary layer per calling app; no favorites, GPS, background sync or persistent PH catalog is created in OsmAnd.

The server's ConnectedApp holds layer/point objects in memory. Its updateMapLayer merges point IDs rather than replacing them, so PH removes its old layer before publishing a fresh one and clears it on return/cancellation. If the PH process is killed, no durable favorite/catalog has been written; the next opening removes a leftover in-memory layer.
Verified source: https://github.com/osmandapp/OsmAnd/blob/master/OsmAnd/src/net/osmand/aidl/ConnectedApp.java .
