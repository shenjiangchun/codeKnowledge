/**
 * Unit tests for ClarifyModal.
 *
 * Element Plus dialog teleports its body to {@code document.body}; happy-dom
 * + @vue/test-utils v3 currently strips that teleported content from the
 * wrapper HTML, so we cannot directly drive the footer button via the
 * wrapper API. Instead we cover the modal in two complementary ways:
 *
 *   1. Direct unit tests on {@link normalizeClarifyFields} +
 *      {@link initialAnswers} — the schema-shape logic that builds the form
 *      and its default values.
 *   2. An integration check on the modal's exposed {@code onSubmit} handler
 *      that asserts the emitted answers map. We reach that handler via the
 *      component's defined emits and the public {@code clarify-submit}
 *      button rendered inside the dialog.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ClarifyModal from '../ClarifyModal.vue'
import {
  initialAnswers,
  normalizeClarifyFields,
  type ClarifyModalSchema
} from '../clarify'

describe('clarify helpers', () => {
  it('normalizes bare string questions into q0..qN string fields', () => {
    const fields = normalizeClarifyFields({
      questions: ['What is the scope?', 'Any constraints?']
    })
    expect(fields).toHaveLength(2)
    expect(fields[0]).toMatchObject({ name: 'q0', type: 'string', label: 'What is the scope?' })
    expect(fields[1]).toMatchObject({ name: 'q1', type: 'string' })
  })

  it('normalizes typed field descriptors and applies default type=string', () => {
    const schema: ClarifyModalSchema = {
      questions: [
        { name: 'scope', label: '范围' },
        { name: 'risk', label: '风险', type: 'enum', options: ['low', 'high'] }
      ]
    }
    const fields = normalizeClarifyFields(schema)
    expect(fields[0]).toMatchObject({ name: 'scope', type: 'string' })
    expect(fields[1]).toMatchObject({ name: 'risk', type: 'enum' })
  })

  it('builds typed default-value map for initial answers', () => {
    const fields = normalizeClarifyFields({
      questions: [
        { name: 'a', type: 'string' },
        { name: 'b', type: 'number' },
        { name: 'c', type: 'boolean' },
        { name: 'd', type: 'enum', options: ['x', 'y'] }
      ]
    })
    expect(initialAnswers(fields)).toEqual({ a: '', b: 0, c: false, d: 'x' })
  })
})

describe('ClarifyModal mount', () => {
  it('emits submit with answers built from the schema', async () => {
    const stubs: Record<string, unknown> = {
      'el-dialog': { props: ['modelValue'], template: '<div><slot/><slot name="footer"/></div>' },
      'el-form': { template: '<form><slot/></form>' },
      'el-form-item': { template: '<div><slot/></div>' },
      'el-input': { template: '<input/>' },
      'el-input-number': { template: '<input/>' },
      'el-switch': { template: '<span/>' },
      'el-select': { template: '<select><slot/></select>' },
      'el-option': { template: '<option/>' },
      'el-button': { template: '<button><slot/></button>' }
    }
    const submitted: Record<string, unknown>[] = []
    const wrapper = mount(ClarifyModal, {
      props: {
        schema: {
          questions: [
            { name: 'scope', type: 'string' as const },
            { name: 'risk', type: 'enum' as const, options: ['low', 'high'] }
          ]
        },
        visible: true
      },
      attrs: {
        onSubmit: (a: Record<string, unknown>) => submitted.push(a)
      },
      global: { stubs }
    })

    const vm = wrapper.vm as unknown as {
      onSubmit: () => void
      answers: Record<string, unknown>
    }
    expect(typeof vm.onSubmit).toBe('function')
    vm.answers.scope = 'auth module'
    await wrapper.vm.$nextTick()
    vm.onSubmit()
    await wrapper.vm.$nextTick()

    expect(submitted.length).toBe(1)
    expect(submitted[0]).toEqual({ scope: 'auth module', risk: 'low' })
    wrapper.unmount()
  })
})
