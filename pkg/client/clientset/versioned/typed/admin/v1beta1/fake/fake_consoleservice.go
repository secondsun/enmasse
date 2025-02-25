/*
 * Copyright 2018-2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

// Code generated by client-gen. DO NOT EDIT.

package fake

import (
	v1beta1 "github.com/enmasseproject/enmasse/pkg/apis/admin/v1beta1"
	v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	labels "k8s.io/apimachinery/pkg/labels"
	schema "k8s.io/apimachinery/pkg/runtime/schema"
	types "k8s.io/apimachinery/pkg/types"
	watch "k8s.io/apimachinery/pkg/watch"
	testing "k8s.io/client-go/testing"
)

// FakeConsoleServices implements ConsoleServiceInterface
type FakeConsoleServices struct {
	Fake *FakeAdminV1beta1
	ns   string
}

var consoleservicesResource = schema.GroupVersionResource{Group: "admin.enmasse.io", Version: "v1beta1", Resource: "consoleservices"}

var consoleservicesKind = schema.GroupVersionKind{Group: "admin.enmasse.io", Version: "v1beta1", Kind: "ConsoleService"}

// Get takes name of the consoleService, and returns the corresponding consoleService object, and an error if there is any.
func (c *FakeConsoleServices) Get(name string, options v1.GetOptions) (result *v1beta1.ConsoleService, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewGetAction(consoleservicesResource, c.ns, name), &v1beta1.ConsoleService{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1beta1.ConsoleService), err
}

// List takes label and field selectors, and returns the list of ConsoleServices that match those selectors.
func (c *FakeConsoleServices) List(opts v1.ListOptions) (result *v1beta1.ConsoleServiceList, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewListAction(consoleservicesResource, consoleservicesKind, c.ns, opts), &v1beta1.ConsoleServiceList{})

	if obj == nil {
		return nil, err
	}

	label, _, _ := testing.ExtractFromListOptions(opts)
	if label == nil {
		label = labels.Everything()
	}
	list := &v1beta1.ConsoleServiceList{ListMeta: obj.(*v1beta1.ConsoleServiceList).ListMeta}
	for _, item := range obj.(*v1beta1.ConsoleServiceList).Items {
		if label.Matches(labels.Set(item.Labels)) {
			list.Items = append(list.Items, item)
		}
	}
	return list, err
}

// Watch returns a watch.Interface that watches the requested consoleServices.
func (c *FakeConsoleServices) Watch(opts v1.ListOptions) (watch.Interface, error) {
	return c.Fake.
		InvokesWatch(testing.NewWatchAction(consoleservicesResource, c.ns, opts))

}

// Create takes the representation of a consoleService and creates it.  Returns the server's representation of the consoleService, and an error, if there is any.
func (c *FakeConsoleServices) Create(consoleService *v1beta1.ConsoleService) (result *v1beta1.ConsoleService, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewCreateAction(consoleservicesResource, c.ns, consoleService), &v1beta1.ConsoleService{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1beta1.ConsoleService), err
}

// Update takes the representation of a consoleService and updates it. Returns the server's representation of the consoleService, and an error, if there is any.
func (c *FakeConsoleServices) Update(consoleService *v1beta1.ConsoleService) (result *v1beta1.ConsoleService, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewUpdateAction(consoleservicesResource, c.ns, consoleService), &v1beta1.ConsoleService{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1beta1.ConsoleService), err
}

// UpdateStatus was generated because the type contains a Status member.
// Add a +genclient:noStatus comment above the type to avoid generating UpdateStatus().
func (c *FakeConsoleServices) UpdateStatus(consoleService *v1beta1.ConsoleService) (*v1beta1.ConsoleService, error) {
	obj, err := c.Fake.
		Invokes(testing.NewUpdateSubresourceAction(consoleservicesResource, "status", c.ns, consoleService), &v1beta1.ConsoleService{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1beta1.ConsoleService), err
}

// Delete takes name of the consoleService and deletes it. Returns an error if one occurs.
func (c *FakeConsoleServices) Delete(name string, options *v1.DeleteOptions) error {
	_, err := c.Fake.
		Invokes(testing.NewDeleteAction(consoleservicesResource, c.ns, name), &v1beta1.ConsoleService{})

	return err
}

// DeleteCollection deletes a collection of objects.
func (c *FakeConsoleServices) DeleteCollection(options *v1.DeleteOptions, listOptions v1.ListOptions) error {
	action := testing.NewDeleteCollectionAction(consoleservicesResource, c.ns, listOptions)

	_, err := c.Fake.Invokes(action, &v1beta1.ConsoleServiceList{})
	return err
}

// Patch applies the patch and returns the patched consoleService.
func (c *FakeConsoleServices) Patch(name string, pt types.PatchType, data []byte, subresources ...string) (result *v1beta1.ConsoleService, err error) {
	obj, err := c.Fake.
		Invokes(testing.NewPatchSubresourceAction(consoleservicesResource, c.ns, name, data, subresources...), &v1beta1.ConsoleService{})

	if obj == nil {
		return nil, err
	}
	return obj.(*v1beta1.ConsoleService), err
}
