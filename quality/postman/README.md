EmoDB http endpoints tests in Postman
======================================

Written by [Bazaarvoice](http://www.bazaarvoice.com)

Introduction
------------

Postman tests are tests written in [Postman](https://www.postman.com/) for testing different business functions
via http endpoints exposed by EmoDB application.

Below can be found two ways of running Postman tests even though all ways provided by Postman are supported. 

Running tests via Postman GUI interface
---------------------------------------
Postman keeps its documents always up to date and provide manual with graphical representation of all screens involved.
Manual for running postman tests via gui can be found on [Postman webpage](https://learning.postman.com/docs/running-collections/intro-to-collection-runs/)

Running tests via command line 
------------------------------
1. Install command line tool which allows running postman tests via command line:

```
npm install -g newman
```

2.Execute preconditions postman collection which setups api keys in case they are missing on server used for testing:

```
newman run "tests_preconditions.postman_collection.json" -e "sor-1-_table-table.postman_environment.json" --env-var "api_key=$EMODB_TEST_SERVER_ADMIN_APIKEY" --env-var "baseurl_dc1=$EMODB_URL_IN_EU_DC --env-var "baseurl_dc2=$EMODB_URL_IN_US_DC --env-var "api_key_no_rights=$EMODB_TEST_SERVER_APIKEY_SOR_READ"
--export-environment "sor-1-_table-table.postman_environment.json"
```
`$EMODB_TEST_SERVER_ADMIN_APIKEY` is admin api key used on the server for running tests against.  
`$EMODB_URL_IN_ EU_DC` and `$EMODB_URL_IN_US_DC` are different data centers where emodb is up and running. `$EMODB_URL_IN_US_DC` is used for tests where facade is under tests.   
`$EMODB_TEST_SERVER_APIKEY_SOR_READ` is api key with only sor read permission. Specify only if this key created
if not it can be omitted.  
`--export-environment` is the parameter where file with postman environment variables should be supplied. 

Run the same on local:
```
newman run "tests_preconditions.postman_collection.json" -e "sor-1-_table-table.postman_environment.json" --env-var "api_key=local_admin"
--export-environment "sor-1-_table-table.postman_environment.json"
```

3. Run existing postman tests collection

```
newman run "$POSTMAN_COLELCTION_NAME" -e "sor-1-_table-table.postman_environment.json" --env-var "api_key=$API_KEY"
```
`$API_KEY` is admin api key that is available on the server under testing.
`$POSTMAN_COLLECTION_NAME` set one of the available postman collection names
e.g.
"emodb_tests_databus.postman_collection.json"
"emodb_tests_databus_get_claimcount.postman_collection.json"
"emodb_tests_databus_get_peek.postman_collection.json"
"emodb_tests_databus_get_poll.postman_collection.json"
"emodb_tests_databus_get_size.postman_collection.json"
"emodb_tests_databus_get_subscription.postman_collection.json"
"emodb_tests_databus_unsubscribe.postman_collection.json"
"emodb_tests_sor.postman_collection.json"
"emodb_tests_sor_facade.postman_collection.json"
"emodb_tests_sor_imports_arbitary_size.postman_collection.json"
"emodb_tests_sor_table.postman_collection.json"
"emodb_tests_sor_tableName.postman_collection.json"

Run the same on local:
```
newman run "$POSTMAN_COLELCTION_NAME" -e "sor-1-_table-table.postman_environment.json" --env-var "api_key=local_admin"
```
