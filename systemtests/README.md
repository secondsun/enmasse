# Systemtests

This repository contains the EnMasse system tests. The tests can be run against an EnMasse instance
running on Kubernetes or OpenShift. The make targets will attempt to use `oc` command line tool and
fallback to `kubectl`.

## Setting up test environment

It is assumed that you have EnMasse running already. If not, have a look at
the [documentation](http://enmasse.io/documentation/master/openshift/#quickstart-messaging-gs).

*NOTE*: On OKD, make sure `oc` is logged in to your cluster with `cluster-admin` privileges. However you should not run the tests with `system:admin` user. You can give the privileges to a standard user :

    oc login -u system:admin https://localhost:8443
    oc adm policy add-cluster-role-to-user cluster-admin developer
    oc login -u developer -p developer https://localhost:8443

## Running all tests

    make SYSTEMTEST_PROFILE=${PROFILE)

##  Running a single test class

    make SYSTEMTEST_PROFILE=${PROFILE) SYSTEMTEST_ARGS="**.SmokeTest"

Where $PROFILE can be:
* systemtests
* systemtests-marathon
* systemtests-upgrade
