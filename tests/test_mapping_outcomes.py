#
# Copyright (c) 2026, Elia Group
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
The outcomes of the CGMES change mapping as a user of the dataframes meets them.

powsybl-core states what every regulation setter does to the change exports in its setter matrix
(cgmes-conversion, src/test/resources/regulation-setter-matrix/expected-outcomes.tsv): EXPORTED, REFUSED with a rule,
or NOT_REPRESENTED. MATRIX_OUTCOMES below are the (outcome, rule) pairs of that file. Each one is either a case here,
reached through the update methods of the dataframes and checked on both change exports (partial SSH and difference
model), or listed in UNREACHABLE with the reason the columns cannot reach it. A refusal is pinned with its remedy, the
text of core's Refusal constant, as pypowsybl reports it.
"""
import pathlib
import re

import pandas as pd
import pytest

import pypowsybl as pp

KEEP_PREVIOUS = {'iidm.import.cgmes.use-previous-values-during-update': 'true'}

# The (outcome, rule) pairs of expected-outcomes.tsv whose outcome a change export decides (UNCHANGED, IIDM_SILENT and
# IIDM_REFUSES are decided by IIDM before any export runs).
MATRIX_OUTCOMES = {
    ('EXPORTED', 'steady-state'), ('EXPORTED', 'echo-1'), ('EXPORTED', 'own-terminal'),
    ('NOT_REPRESENTED', 'local-target'),
    ('REFUSED', 'import-gives-regulation'), ('REFUSED', 'echo-3'), ('REFUSED', 'vsc-no-control-flag'),
    ('REFUSED', 'terminal-eq'), ('REFUSED', 'slope-no-property'), ('REFUSED', 'own-terminal'),
    ('REFUSED', 'mode-eq'), ('REFUSED', 'deadband-not-read'), ('REFUSED', 'rtc-reactive-power'),
    ('REFUSED', 'local-target'), ('REFUSED', 'cgmes-mode'), ('REFUSED', 'no-control'), ('REFUSED', 'no-mode'),
    ('REFUSED', 'equivalent-shunt'),
}

# The remedy of each refusal reached here: Refusal.getRemedy() of powsybl-core, word for word.
REMEDIES = {
    'terminal-eq': 'export the equipment model with the change (a full CGMES export), or keep the regulating terminal'
                   ' the import set',
    'mode-eq': 'export the equipment model with the change (a full CGMES export), or keep the mode the import set',
    'own-terminal': "regulate the converter's own terminal (VoltageRegulation.setTerminal(converter terminal, target)),"
                    ' or none',
    'slope-no-property': 'leave the slope as the import set it',
    'vsc-no-control-flag': 'let it regulate, in REACTIVE_POWER mode with its reactive power target for a converter'
                           ' that must not regulate voltage',
    'no-control': 'keep its regulation as the import left it; to change it, give it a VoltageRegulation (not'
                  ' regulating) first and export the full model (EQ and SSH): a full export writes a RegulatingControl'
                  ' only for equipment that has a VoltageRegulation',
}

UNREACHABLE = {
    ('EXPORTED', 'echo-1'): 'the columns write through the VoltageRegulation, never through a deprecated setter',
    ('REFUSED', 'echo-3'): 'the columns write through the VoltageRegulation, never through a deprecated setter',
    ('REFUSED', 'import-gives-regulation'): 'equipment imported from CGMES that the import gives a regulation always'
                                            ' has one, and no column removes it',
    ('REFUSED', 'deadband-not-read'): 'no column sets the deadband of a generator, a static var compensator or a'
                                      ' converter',
    ('REFUSED', 'rtc-reactive-power'): 'no column sets the mode of a ratio tap changer, and no bundled network'
                                       ' imports one regulating reactive power',
    ('REFUSED', 'local-target'): 'target_v of a generator regulating elsewhere writes the target of its regulation'
                                 ' (exported), not a local target',
    ('REFUSED', 'cgmes-mode'): 'no column sets the regulation mode of a generator',
    ('REFUSED', 'no-mode'): 'a regulation without a mode in a variant is one created in another variant, and every'
                            ' holder that has a CGMES regulating control comes with its regulation from the import',
    ('REFUSED', 'equivalent-shunt'): 'no network of the test data has a shunt compensator imported from an'
                                     ' EquivalentShunt',
}


def _cgmes(folder: pathlib.Path, network: pp.network.Network) -> pp.network.Network:
    if not folder.exists():
        folder.mkdir(parents=True)
        network.save(str(folder / folder.name), format='CGMES')
    return pp.network.load(str(folder))


def _four(folder):
    return _cgmes(folder / 'four', pp.network.create_four_substations_node_breaker_network())


def _four_with_slope(folder):
    n = _four(folder)
    n.create_extensions('voltagePerReactivePowerControl', id='SVC', slope=0.3)
    return n


def _four_vsc_regulating_a_generator(folder):
    """VSC1 regulating the terminal of GH1, through a CGMES round trip."""
    n = pp.network.create_four_substations_node_breaker_network()
    n.update_vsc_converter_stations(id='VSC1', regulated_element_id='GH1')
    return _cgmes(folder / 'vsc_remote', n)


# (outcome, rule, network, dataframe, element, values of the update)
CASES = [
    ('EXPORTED', 'steady-state', _four, 'generators', 'GH1', {'target_v': 401.0}),
    ('EXPORTED', 'steady-state', _four, 'ratio_tap_changers', 'TWT', {'target_v': 400.0}),
    ('EXPORTED', 'own-terminal', _four, 'vsc_converter_stations', 'VSC1',
     {'voltage_regulator_on': False, 'target_q': 33.0}),
    ('NOT_REPRESENTED', 'local-target', _four, 'vsc_converter_stations', 'VSC2', {'target_v': 405.0}),
    ('REFUSED', 'terminal-eq', _four, 'generators', 'GH1', {'regulated_element_id': 'GH2'}),
    ('REFUSED', 'mode-eq', _four, 'static_var_compensators', 'SVC',
     {'target_q': 10.0, 'regulation_mode': 'REACTIVE_POWER'}),
    ('REFUSED', 'own-terminal', _four, 'vsc_converter_stations', 'VSC1', {'regulated_element_id': 'GH1'}),
    ('REFUSED', 'slope-no-property', _four_with_slope, 'voltagePerReactivePowerControl', 'SVC', {'slope': 0.6}),
    ('REFUSED', 'vsc-no-control-flag', _four_vsc_regulating_a_generator, 'vsc_converter_stations', 'VSC1',
     {'voltage_regulator_on': False}),
    # SHUNT has no RegulatingControl in its CGMES model
    ('REFUSED', 'no-control', _four, 'shunt_compensators', 'SHUNT', {'target_deadband': 2.0}),
]

ROUTES = {
    'ssh': (lambda recorder: recorder.to_ssh(), 'update_SSH.xml', KEEP_PREVIOUS),
    'diff': (lambda recorder: recorder.to_cgmes_diff(), 'update_SSH_DIFF.xml', None),
}

# a CGMES property statement: <cim:Class.property ...
PROPERTY = re.compile(r'<cim:\w+\.\w+')


@pytest.fixture(scope='module', name='folder')
def fixture_folder(tmp_path_factory):
    return tmp_path_factory.mktemp('mapping_outcomes')


def _update(n: pp.network.Network, dataframe: str, element: str, values: dict) -> None:
    if dataframe in pp.network.get_extensions_names():
        n.update_extensions(dataframe, id=element, **values)
    else:
        getattr(n, 'update_' + dataframe)(id=element, **values)


def _columns(n: pp.network.Network, dataframe: str, element: str, values: dict) -> pd.Series:
    frame = n.get_extensions(dataframe) if dataframe in pp.network.get_extensions_names() \
        else getattr(n, 'get_' + dataframe)()
    return frame.loc[element, list(values)]


def test_every_matrix_outcome_is_a_case_or_unreachable():
    cases = {case[:2] for case in CASES}
    assert not cases & set(UNREACHABLE)
    assert cases | set(UNREACHABLE) == MATRIX_OUTCOMES
    assert {rule for outcome, rule in cases if outcome == 'REFUSED'} == set(REMEDIES)


@pytest.mark.parametrize('route', ROUTES)
@pytest.mark.parametrize('outcome, rule, network, dataframe, element, values', CASES,
                         ids=[f'{c[0]}-{c[1]}-{c[3]}' for c in CASES])
def test_outcome(folder, route, outcome, rule, network, dataframe, element, values):
    export, file_name, parameters = ROUTES[route]
    sender = network(folder)
    with sender.event_recorder() as recorder:
        _update(sender, dataframe, element, values)
        assert not recorder.events.empty
        if outcome == 'REFUSED':
            with pytest.raises(pp.PyPowsyblError, match=re.escape('Remedy: ' + REMEDIES[rule])):
                export(recorder)
            return
        document = export(recorder)
    if outcome == 'NOT_REPRESENTED':
        assert not PROPERTY.search(document), f'{rule}: a change the SSH does not represent was written'
        return
    assert PROPERTY.search(document)
    receiver = network(folder)
    receiver.update_from_string(document, file_name, parameters=parameters)
    pd.testing.assert_series_equal(_columns(receiver, dataframe, element, values),
                                   _columns(sender, dataframe, element, values))
